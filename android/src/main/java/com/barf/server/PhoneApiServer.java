package com.barf.server;

import android.content.Context;
import android.util.Log;

import com.barf.YoloBridge;
import com.barf.SimpleWebSocketServer;
import com.barf.VideoStreamServer;
import com.barf.runtime.JsRuntime;
import com.barf.runtime.WasmRuntime;
import com.barf.serial.UsbSerialManager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Slim HTTP/WebSocket API server for the BARF desktop companion app.
 *
 * Endpoints:
 *   POST /api/wasm       — deploy .wasm binary (stub)
 *   POST /api/js/run     — execute JS snippet
 *   POST /api/js/stop    — stop JS execution
 *   POST /api/serial     — write to serial (delegates to UsbSerialManager)
 *   POST /api/firmware   — flash ESP32 over USB-OTG
 *   GET  /api/status     — health, FPS, serial state
 *   WS   /api/events     — detection JSON push, robot state updates
 *   WS   /api/serial     — bidirectional serial relay
 */
public class PhoneApiServer extends NanoHTTPD {
    private static final String TAG = "PhoneApiServer";

    private final Context context;
    private SimpleWebSocketServer webSocketServer;
    private VideoStreamServer videoStreamServer;
    private JsRuntime jsRuntime;
    private WasmRuntime wasmRuntime;
    private UsbSerialManager usbSerialManager;
    private boolean isOnline = false;
    private final java.util.List<Thread> streamThreads = new java.util.concurrent.CopyOnWriteArrayList<>();

    // Callback for robot/serial actions
    private ServerCallback callback;

    public interface ServerCallback {
        void onMove(String direction, float speed);
        void onRotate(String direction, float speed);
        void onRobotStop();
        void onSwitchCamera();
        int getCameraFacing();
    }

    public PhoneApiServer(Context context, int port) {
        super(port);
        this.context = context;
        this.videoStreamServer = new VideoStreamServer();
        Log.i(TAG, "Phone API server created on port " + port);
    }

    public void setCallback(ServerCallback callback) {
        this.callback = callback;
    }

    public void setJsRuntime(JsRuntime jsRuntime) {
        this.jsRuntime = jsRuntime;
    }

    public void setWasmRuntime(WasmRuntime wasmRuntime) {
        this.wasmRuntime = wasmRuntime;
    }

    public void setUsbSerialManager(UsbSerialManager manager) {
        this.usbSerialManager = manager;
    }

    public VideoStreamServer getVideoStreamServer() {
        return videoStreamServer;
    }

    public SimpleWebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    public void start() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            isOnline = true;
            Log.i(TAG, "HTTP server started on port " + getListeningPort());

            webSocketServer = new SimpleWebSocketServer(8081);
            webSocketServer.setNewClientListener(conn -> {
                // Push current serial status immediately so the client doesn't
                // have to wait for the next connect/disconnect event.
                try {
                    JsonObject status = new JsonObject();
                    status.addProperty("type", "serial_status");
                    status.addProperty("connected", usbSerialManager != null && usbSerialManager.isConnected());
                    conn.send(status.toString());
                } catch (Exception e) {
                    Log.w(TAG, "Failed to send initial serial status: " + e.getMessage());
                }
            });
            webSocketServer.setMessageListener(message -> {
                try {
                    JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
                    if ("write".equals(msg.has("action") ? msg.get("action").getAsString() : null)) {
                        String data = msg.has("data") ? msg.get("data").getAsString() : "";
                        if (!data.isEmpty()) serialWrite(data);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "WS message parse error: " + e.getMessage());
                }
            });
            webSocketServer.start();
            Log.i(TAG, "WebSocket server started on port 8081");

            videoStreamServer.start();
            Log.i(TAG, "Video stream server started");

        } catch (IOException e) {
            Log.e(TAG, "Failed to start server: " + e.getMessage());
            isOnline = false;
        }
    }

    public void stop() {
        isOnline = false;
        stopStreaming();
        try { super.stop(); } catch (Exception ignored) {}

        if (webSocketServer != null) {
            webSocketServer.shutdown();
        }

        if (videoStreamServer != null) {
            videoStreamServer.stop();
        }

        Log.i(TAG, "Servers stopped");
    }

    private void stopStreaming() {
        for (Thread t : streamThreads) {
            t.interrupt();
        }
        streamThreads.clear();
    }

    public boolean isOnline() {
        return isOnline;
    }

    /**
     * Push detection JSON to JS/WASM runtimes and broadcast to WebSocket clients.
     */
    public void pushDetections(String detectionsJson) {
        if (detectionsJson == null) detectionsJson = "[]";

        if (jsRuntime != null) {
            jsRuntime.pushDetections(detectionsJson);
        }

        // Forward to WASM runtime if loaded
        if (wasmRuntime != null && wasmRuntime.isLoaded()) {
            wasmRuntime.onFrame(detectionsJson);
        }

        if (webSocketServer != null) {
            try {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "detections");
                msg.addProperty("timestamp", System.currentTimeMillis());
                msg.addProperty("detections", detectionsJson);
                webSocketServer.broadcast(msg.toString());
            } catch (Exception e) {
                Log.w(TAG, "Failed to broadcast detections: " + e.getMessage());
            }
        }
    }

    /**
     * Broadcast serial data from ESP32 to all WebSocket clients.
     * Called by UsbSerialManager listener.
     */
    public void broadcastSerialRx(String line) {
        if (webSocketServer == null) return;
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "serial_rx");
            msg.addProperty("data", line);
            msg.addProperty("timestamp", System.currentTimeMillis());
            webSocketServer.broadcast(msg.toString());
        } catch (Exception e) {
            Log.w(TAG, "Failed to broadcast serial_rx: " + e.getMessage());
        }
    }

    /**
     * Broadcast serial connection status to all WebSocket clients.
     */
    public void broadcastSerialStatus(boolean connected) {
        if (webSocketServer == null) return;
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "serial_status");
            msg.addProperty("connected", connected);
            webSocketServer.broadcast(msg.toString());
        } catch (Exception e) {
            Log.w(TAG, "Failed to broadcast serial_status: " + e.getMessage());
        }
    }

    /**
     * Send data to ESP32 over USB serial.
     */
    public void serialWrite(String data) {
        if (usbSerialManager != null && usbSerialManager.isConnected()) {
            usbSerialManager.write(data);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        Log.d(TAG, method + " " + uri);

        // Handle CORS preflight
        if (method == Method.OPTIONS) {
            Response resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
            resp.addHeader("Access-Control-Allow-Origin", "*");
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            resp.addHeader("Access-Control-Allow-Headers", "Content-Type");
            resp.addHeader("Access-Control-Max-Age", "86400");
            return resp;
        }

        try {
            if (uri.startsWith("/api/")) {
                return handleApi(session, uri, method);
            }
            return createJsonResponse(Response.Status.NOT_FOUND, errorJson("Not found: " + uri));
        } catch (Exception e) {
            Log.e(TAG, "Error handling request: " + e.getMessage(), e);
            return createJsonResponse(Response.Status.INTERNAL_ERROR, errorJson(e.getMessage()));
        }
    }

    private Response handleApi(IHTTPSession session, String uri, Method method) {
        switch (uri) {
            case "/api/status":
                return handleStatus();
            case "/api/video":
                if (method == Method.GET) return handleVideoStream();
                break;
            case "/api/js/run":
                if (method == Method.POST) return handleJsRun(session);
                break;
            case "/api/js/stop":
                if (method == Method.POST) return handleJsStop();
                break;
            case "/api/serial":
                if (method == Method.POST) return handleSerialWrite(session);
                if (method == Method.GET) return handleSerialStatus();
                break;
            case "/api/robot/move":
                if (method == Method.POST) return handleRobotMove(session);
                break;
            case "/api/robot/rotate":
                if (method == Method.POST) return handleRobotRotate(session);
                break;
            case "/api/robot/stop":
                if (method == Method.POST) return handleRobotStop();
                break;
            case "/api/robot/camera/switch":
                if (method == Method.POST) return handleCameraSwitch();
                break;
            case "/api/detection/yolo":
                if (method == Method.POST) return handleDetectionYolo(session);
                break;
            case "/api/detection/apriltag":
                if (method == Method.POST) return handleDetectionAprilTag(session);
                break;
        }
        return createJsonResponse(Response.Status.NOT_FOUND, errorJson("API endpoint not found: " + uri));
    }

    private Response handleVideoStream() {
        PipedInputStream pipedIn;
        PipedOutputStream pipedOut;
        try {
            pipedIn = new PipedInputStream(65536);
            pipedOut = new PipedOutputStream(pipedIn);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create MJPEG pipe: " + e.getMessage());
            return createJsonResponse(Response.Status.INTERNAL_ERROR, errorJson("Stream init failed"));
        }

        Thread streamThread = new Thread(() -> {
            try {
                writeMjpegFrames(pipedOut);
            } finally {
                streamThreads.remove(Thread.currentThread());
            }
        }, "mjpeg-stream");
        streamThread.setDaemon(true);
        streamThreads.add(streamThread);
        streamThread.start();

        Response resp = newChunkedResponse(Response.Status.OK,
                "multipart/x-mixed-replace;boundary=frame", pipedIn);
        resp.addHeader("Access-Control-Allow-Origin", "*");
        return resp;
    }

    private void writeMjpegFrames(OutputStream out) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] frame = videoStreamServer.getNextFrame(500);
                if (frame == null) {
                    continue;
                }
                writeMjpegFrame(out, frame);
            }
        } catch (IOException e) {
            Log.d(TAG, "MJPEG client disconnected: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try { out.close(); } catch (IOException ignored) {}
        }
    }

    private void writeMjpegFrame(OutputStream out, byte[] jpeg) throws IOException {
        String header = "--frame\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + jpeg.length + "\r\n"
                + "\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(jpeg);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private Response handleStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("server", "BARF Phone API");
        status.addProperty("status", "online");
        status.addProperty("timestamp", System.currentTimeMillis());
        status.addProperty("httpPort", getListeningPort());
        status.addProperty("wsPort", 8081);
        status.addProperty("jsRunning", jsRuntime != null && jsRuntime.isRunning());
        status.addProperty("serialConnected", usbSerialManager != null && usbSerialManager.isConnected());
        if (webSocketServer != null) {
            status.addProperty("wsClients", webSocketServer.getClientCount());
        }
        status.addProperty("yoloEnabled", YoloBridge.isYoloEnabled());
        status.addProperty("apriltagEnabled", YoloBridge.isAprilTagEnabled());
        if (callback != null) {
            status.addProperty("cameraFacing", callback.getCameraFacing());
        }
        return createJsonResponse(Response.Status.OK, status.toString());
    }

    private Response handleSerialWrite(IHTTPSession session) {
        try {
            String body = getBody(session);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String data = json.has("data") ? json.get("data").getAsString() : "";

            if (data.isEmpty()) {
                return createJsonResponse(Response.Status.BAD_REQUEST, errorJson("No data provided"));
            }

            serialWrite(data);

            JsonObject resp = new JsonObject();
            resp.addProperty("success", true);
            return createJsonResponse(Response.Status.OK, resp.toString());
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, errorJson(e.getMessage()));
        }
    }

    private Response handleSerialStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("connected", usbSerialManager != null && usbSerialManager.isConnected());
        return createJsonResponse(Response.Status.OK, status.toString());
    }

    private Response handleJsRun(IHTTPSession session) {
        try {
            String body = getBody(session);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String script = json.has("script") ? json.get("script").getAsString() : "";

            if (script.isEmpty()) {
                return createJsonResponse(Response.Status.BAD_REQUEST, errorJson("No script provided"));
            }

            if (jsRuntime == null) {
                return createJsonResponse(Response.Status.INTERNAL_ERROR, errorJson("JS runtime not initialized"));
            }

            if (jsRuntime.isRunning()) {
                return createJsonResponse(Response.Status.BAD_REQUEST, errorJson("Script already running"));
            }

            jsRuntime.execute(script);

            JsonObject resp = new JsonObject();
            resp.addProperty("success", true);
            resp.addProperty("message", "Script started");
            return createJsonResponse(Response.Status.OK, resp.toString());

        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, errorJson(e.getMessage()));
        }
    }

    private Response handleJsStop() {
        if (jsRuntime != null && jsRuntime.isRunning()) {
            jsRuntime.stop();
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("message", "Script stopped");
        return createJsonResponse(Response.Status.OK, resp.toString());
    }

    private Response handleRobotMove(IHTTPSession session) {
        if (callback == null) {
            return createJsonResponse(Response.Status.INTERNAL_ERROR, errorJson("Robot controller not available"));
        }
        try {
            String body = getBody(session);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String direction = json.has("direction") ? json.get("direction").getAsString() : "";
            float speed = json.has("speed") ? json.get("speed").getAsFloat() : 0.5f;
            callback.onMove(direction, speed);
            JsonObject resp = new JsonObject();
            resp.addProperty("success", true);
            return createJsonResponse(Response.Status.OK, resp.toString());
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, errorJson(e.getMessage()));
        }
    }

    private Response handleRobotRotate(IHTTPSession session) {
        if (callback == null) {
            return createJsonResponse(Response.Status.INTERNAL_ERROR, errorJson("Robot controller not available"));
        }
        try {
            String body = getBody(session);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String direction = json.has("direction") ? json.get("direction").getAsString() : "";
            float speed = json.has("speed") ? json.get("speed").getAsFloat() : 0.5f;
            callback.onRotate(direction, speed);
            JsonObject resp = new JsonObject();
            resp.addProperty("success", true);
            return createJsonResponse(Response.Status.OK, resp.toString());
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, errorJson(e.getMessage()));
        }
    }

    private Response handleRobotStop() {
        if (callback != null) callback.onRobotStop();
        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        return createJsonResponse(Response.Status.OK, resp.toString());
    }

    private Response handleCameraSwitch() {
        if (callback != null) callback.onSwitchCamera();
        JsonObject resp = new JsonObject();
        resp.addProperty("success", true);
        resp.addProperty("cameraFacing", callback != null ? callback.getCameraFacing() : -1);
        return createJsonResponse(Response.Status.OK, resp.toString());
    }

    private Response handleDetectionYolo(IHTTPSession session) {
        try {
            String body = getBody(session);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            boolean enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
            YoloBridge.setYoloEnabled(enabled);
            JsonObject resp = new JsonObject();
            resp.addProperty("success", true);
            resp.addProperty("enabled", YoloBridge.isYoloEnabled());
            return createJsonResponse(Response.Status.OK, resp.toString());
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, errorJson(e.getMessage()));
        }
    }

    private Response handleDetectionAprilTag(IHTTPSession session) {
        try {
            String body = getBody(session);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            boolean enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
            YoloBridge.setAprilTagEnabled(enabled);
            JsonObject resp = new JsonObject();
            resp.addProperty("success", true);
            resp.addProperty("enabled", YoloBridge.isAprilTagEnabled());
            return createJsonResponse(Response.Status.OK, resp.toString());
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, errorJson(e.getMessage()));
        }
    }

    private String getBody(IHTTPSession session) throws IOException {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (ResponseException e) {
            throw new IOException("Failed to parse body", e);
        }
        String body = files.get("postData");
        return body != null ? body : "";
    }

    private String errorJson(String message) {
        JsonObject err = new JsonObject();
        err.addProperty("success", false);
        err.addProperty("error", message);
        err.addProperty("timestamp", System.currentTimeMillis());
        return err.toString();
    }

    private Response createJsonResponse(Response.Status status, String json) {
        Response resp = newFixedLengthResponse(status, "application/json", json);
        resp.addHeader("Access-Control-Allow-Origin", "*");
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return resp;
    }
}
