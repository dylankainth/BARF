/**
 * SimpleHttpServer.java
 * 
 * Minimal HTTP server for serving static HTML and basic API endpoints.
 */
package com.tencent.yolo11ncnn;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class SimpleHttpServer extends NanoHTTPD {
    private static final String TAG = "SimpleHttpServer";
    
    private Context context;
    private AssetManager assetManager;
    private SimpleWebSocketServer webSocketServer;
    private boolean isOnline = false;
    private RobotControlCallback robotCallback;
    private VideoStreamServer videoStreamServer;
    private String robotIp = "192.168.1.100"; // Default robot IP
    
    // Script storage and execution
    private static final String SCRIPT_PREFS = "RobotScriptPrefs";
    private static final String SCRIPT_KEY = "saved_script";
    private SharedPreferences scriptPrefs;
    private RhinoScriptExecutor scriptExecutor;
    
    /**
     * Callback interface for robot control commands.
     */
    public interface RobotControlCallback {
        void onMove(String direction, float speed);
        void onRotate(String direction, float speed);
        void onStop();
        void onCameraSwitch();
        RobotStatus getRobotStatus();
    }
    
    /**
     * Robot status data.
     */
    public static class RobotStatus {
        public boolean isMoving;
        public String lastCommand;
        public int cameraFacing; // 0 = back, 1 = front
        public long timestamp;
        
        public RobotStatus() {
            this.isMoving = false;
            this.lastCommand = "none";
            this.cameraFacing = 0;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    // MIME types for static files
    private static final Map<String, String> MIME_TYPES = new HashMap<>();
    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("ico", "image/x-icon");
    }
    
    public SimpleHttpServer(Context context, int port) {
        super(port);
        this.context = context;
        this.assetManager = context.getAssets();
        this.videoStreamServer = new VideoStreamServer();
        this.scriptPrefs = context.getSharedPreferences(SCRIPT_PREFS, Context.MODE_PRIVATE);
        Log.i(TAG, "Simple HTTP server initialized on port " + port);
    }
    
    /**
     * Set the robot control callback.
     */
    public void setRobotControlCallback(RobotControlCallback callback) {
        this.robotCallback = callback;
        
        // Initialize Rhino script executor with robot commands
        this.scriptExecutor = new RhinoScriptExecutor(new RhinoScriptExecutor.RobotCommandCallback() {
            @Override
            public void onMove(String direction, float speed) {
                if (robotCallback != null) {
                    robotCallback.onMove(direction, speed);
                }
            }
            
            @Override
            public void onRotate(String direction, float speed) {
                if (robotCallback != null) {
                    robotCallback.onRotate(direction, speed);
                }
            }
            
            @Override
            public void onStop() {
                if (robotCallback != null) {
                    robotCallback.onStop();
                }
            }
        });
    }
    
    /**
     * Get the video stream server.
     */
    public VideoStreamServer getVideoStreamServer() {
        return videoStreamServer;
    }
    
    /**
     * Get the robot IP address.
     */
    public String getRobotIp() {
        return robotIp;
    }
    
    /**
     * Start the HTTP server and WebSocket server.
     */
    public void startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            isOnline = true;
            Log.i(TAG, "HTTP Server started on port " + getListeningPort());
            
            // Start WebSocket server on port 8081
            webSocketServer = new SimpleWebSocketServer(8081);
            webSocketServer.start();
            Log.i(TAG, "WebSocket Server started on port 8081");
            
            // Start video stream server
            if (videoStreamServer != null) {
                videoStreamServer.start();
                Log.i(TAG, "Video Stream Server started");
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server: " + e.getMessage());
            isOnline = false;
        }
    }
    
    /**
     * Stop the servers.
     */
    public void stopServer() {
        stop();
        isOnline = false;
        
        if (webSocketServer != null) {
            webSocketServer.shutdown();
        }
        
        if (videoStreamServer != null) {
            videoStreamServer.stop();
        }
        
        Log.i(TAG, "Servers stopped");
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        
        Log.d(TAG, method + " " + uri);
        
        try {
            // Handle video stream
            if (uri.startsWith("/stream/")) {
                return handleStreamRequest(session, uri, method);
            }
            
            // Handle API routes
            if (uri.startsWith("/api/")) {
                return handleApiRequest(session, uri, method);
            }
            
            // Serve static files
            return serveStaticFile(uri);
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling request: " + e.getMessage(), e);
            return createJsonResponse(Response.Status.INTERNAL_ERROR, 
                    createErrorJson("Internal server error: " + e.getMessage()));
        }
    }
    
    /**
     * Handle stream requests (video, images, etc).
     */
    private Response handleStreamRequest(IHTTPSession session, String uri, Method method) {
        if (uri.equals("/stream/video")) {
            return handleVideoStream(session);
        }
        
        return createJsonResponse(Response.Status.NOT_FOUND, 
                createErrorJson("Stream endpoint not found: " + uri));
    }
    
    /**
     * Handle MJPEG video stream.
     */
    private Response handleVideoStream(IHTTPSession session) {
        Log.i(TAG, "Client connected to video stream");
        return new MjpegResponse(videoStreamServer);
    }
    
    /**
     * Handle API requests.
     */
    private Response handleApiRequest(IHTTPSession session, String uri, Method method) {
        switch (uri) {
            case "/api/status":
                if (method == Method.GET) {
                    return handleStatusGet();
                }
                break;
                
            case "/api/message":
                if (method == Method.POST) {
                    return handleMessagePost(session);
                }
                break;
                
            case "/api/broadcast":
                if (method == Method.POST) {
                    return handleBroadcastPost(session);
                }
                break;
                
            case "/api/robot/move":
                if (method == Method.POST) {
                    return handleRobotMove(session);
                }
                break;
                
            case "/api/robot/rotate":
                if (method == Method.POST) {
                    return handleRobotRotate(session);
                }
                break;
                
            case "/api/robot/stop":
                if (method == Method.POST) {
                    return handleRobotStop();
                }
                break;
                
            case "/api/robot/camera/switch":
                if (method == Method.POST) {
                    return handleCameraSwitch();
                }
                break;
                
            case "/api/robot/status":
                if (method == Method.GET) {
                    return handleRobotStatus();
                }
                break;
                
            case "/api/robot/ip":
                if (method == Method.GET) {
                    return handleRobotIpGet();
                } else if (method == Method.POST) {
                    return handleRobotIpPost(session);
                }
                break;
                
            case "/api/robot/test":
                if (method == Method.GET) {
                    return handleRobotTest();
                }
                break;
                
            // Script API endpoints
            case "/api/script":
                if (method == Method.GET) {
                    return handleScriptGet();
                } else if (method == Method.POST) {
                    return handleScriptPost(session);
                }
                break;
                
            case "/api/script/run":
                if (method == Method.POST) {
                    return handleScriptRun(session);
                }
                break;
                
            case "/api/script/stop":
                if (method == Method.POST) {
                    return handleScriptStop();
                }
                break;
                
            case "/api/script/status":
                if (method == Method.GET) {
                    return handleScriptStatus();
                }
                break;
        }
        
        return createJsonResponse(Response.Status.NOT_FOUND, 
                createErrorJson("API endpoint not found: " + uri));
    }
    
    /**
     * Handle GET /api/status
     */
    private Response handleStatusGet() {
        JsonObject status = new JsonObject();
        status.addProperty("server", "Simple HTTP/WebSocket Server");
        status.addProperty("status", "online");
        status.addProperty("timestamp", System.currentTimeMillis());
        status.addProperty("httpPort", getListeningPort());
        status.addProperty("webSocketPort", 8081);
        
        if (webSocketServer != null) {
            status.addProperty("webSocketClients", webSocketServer.getClientCount());
        }
        
        return createJsonResponse(Response.Status.OK, status.toString());
    }
    
    /**
     * Handle POST /api/message
     */
    private Response handleMessagePost(IHTTPSession session) {
        try {
            String body = getRequestBody(session);
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Message received");
            response.addProperty("receivedBody", body);
            response.addProperty("timestamp", System.currentTimeMillis());
            
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, 
                    createErrorJson("Failed to process message: " + e.getMessage()));
        }
    }
    
    /**
     * Handle POST /api/broadcast
     */
    private Response handleBroadcastPost(IHTTPSession session) {
        try {
            String body = getRequestBody(session);
            
            if (webSocketServer != null) {
                webSocketServer.broadcast("HTTP Broadcast: " + body);
            }
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Broadcast sent to WebSocket clients");
            response.addProperty("clientCount", webSocketServer != null ? webSocketServer.getClientCount() : 0);
            
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, 
                    createErrorJson("Failed to broadcast: " + e.getMessage()));
        }
    }
    
    /**
     * Handle POST /api/robot/move
     * Body: { "direction": "forward|backward|left|right", "speed": 0.0-1.0 }
     */
    private Response handleRobotMove(IHTTPSession session) {
        try {
            String body = getRequestBody(session);
            JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            
            String direction = json.get("direction").getAsString();
            float speed = json.get("speed").getAsFloat();
            
            if (robotCallback != null) {
                robotCallback.onMove(direction, speed);
                broadcastRobotCommand("move", direction, speed);
            }
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("command", "move");
            response.addProperty("direction", direction);
            response.addProperty("speed", speed);
            
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, 
                    createErrorJson("Failed to move: " + e.getMessage()));
        }
    }
    
    /**
     * Handle POST /api/robot/rotate
     * Body: { "direction": "left|right", "speed": 0.0-1.0 }
     */
    private Response handleRobotRotate(IHTTPSession session) {
        try {
            String body = getRequestBody(session);
            JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            
            String direction = json.get("direction").getAsString();
            float speed = json.get("speed").getAsFloat();
            
            if (robotCallback != null) {
                robotCallback.onRotate(direction, speed);
                broadcastRobotCommand("rotate", direction, speed);
            }
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("command", "rotate");
            response.addProperty("direction", direction);
            response.addProperty("speed", speed);
            
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, 
                    createErrorJson("Failed to rotate: " + e.getMessage()));
        }
    }
    
    /**
     * Handle POST /api/robot/stop
     */
    private Response handleRobotStop() {
        try {
            if (robotCallback != null) {
                robotCallback.onStop();
                broadcastRobotCommand("stop", "", 0);
            }
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("command", "stop");
            
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, 
                    createErrorJson("Failed to stop: " + e.getMessage()));
        }
    }
    
    /**
     * Handle POST /api/robot/camera/switch
     */
    private Response handleCameraSwitch() {
        try {
            if (robotCallback != null) {
                robotCallback.onCameraSwitch();
                broadcastRobotCommand("camera_switch", "", 0);
            }
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("command", "camera_switch");
            
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, 
                    createErrorJson("Failed to switch camera: " + e.getMessage()));
        }
    }
    
    /**
     * Handle GET /api/robot/status
     */
    private Response handleRobotStatus() {
        try {
            RobotStatus status = robotCallback != null ? robotCallback.getRobotStatus() : new RobotStatus();
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("isMoving", status.isMoving);
            response.addProperty("lastCommand", status.lastCommand);
            response.addProperty("cameraFacing", status.cameraFacing);
            response.addProperty("timestamp", status.timestamp);
            
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST, 
                    createErrorJson("Failed to get status: " + e.getMessage()));
        }
    }
    
    /**
     * Handle GET /api/robot/ip
     */
    private Response handleRobotIpGet() {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("robotIp", robotIp);
        return createJsonResponse(Response.Status.OK, response.toString());
    }
    
    /**
     * Handle POST /api/robot/ip
     */
    private Response handleRobotIpPost(IHTTPSession session) {
        try {
            String requestBody = getRequestBody(session);
            JsonObject request = JsonParser.parseString(requestBody).getAsJsonObject();
            
            if (request.has("ip")) {
                String newIp = request.get("ip").getAsString();
                if (isValidIpAddress(newIp)) {
                    robotIp = newIp;
                    Log.i(TAG, "Robot IP set to: " + robotIp);
                    
                    JsonObject response = new JsonObject();
                    response.addProperty("success", true);
                    response.addProperty("robotIp", robotIp);
                    return createJsonResponse(Response.Status.OK, response.toString());
                } else {
                    return createJsonResponse(Response.Status.BAD_REQUEST,
                            createErrorJson("Invalid IP address format"));
                }
            } else {
                return createJsonResponse(Response.Status.BAD_REQUEST,
                        createErrorJson("Missing 'ip' parameter"));
            }
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST,
                    createErrorJson("Failed to set robot IP: " + e.getMessage()));
        }
    }
    
    /**
     * Handle GET /api/robot/test
     */
    private Response handleRobotTest() {
        try {
            // Test if we can reach the robot (basic connectivity check)
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("robotIp", robotIp);
            response.addProperty("udpPort", 4210);
            response.addProperty("message", "Robot IP configured (UDP test not implemented yet)");
            
            Log.i(TAG, "Robot test - IP: " + robotIp + ", Port: 4210");
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST,
                    createErrorJson("Robot test failed: " + e.getMessage()));
        }
    }
    
    // ============================================
    // Script API Handlers
    // ============================================
    
    /**
     * Handle GET /api/script - Load saved script
     */
    private Response handleScriptGet() {
        String script = scriptPrefs.getString(SCRIPT_KEY, "");
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("script", script);
        return createJsonResponse(Response.Status.OK, response.toString());
    }
    
    /**
     * Handle POST /api/script - Save script
     */
    private Response handleScriptPost(IHTTPSession session) {
        try {
            String body = getRequestBody(session);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String script = json.has("script") ? json.get("script").getAsString() : "";
            
            scriptPrefs.edit().putString(SCRIPT_KEY, script).apply();
            Log.i(TAG, "Script saved (" + script.length() + " chars)");
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Script saved");
            response.addProperty("length", script.length());
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST,
                    createErrorJson("Failed to save script: " + e.getMessage()));
        }
    }
    
    /**
     * Handle POST /api/script/run - Execute script using Rhino JS engine
     */
    private Response handleScriptRun(IHTTPSession session) {
        if (scriptExecutor == null) {
            return createJsonResponse(Response.Status.INTERNAL_ERROR,
                    createErrorJson("Script executor not initialized"));
        }
        
        if (scriptExecutor.isRunning()) {
            return createJsonResponse(Response.Status.BAD_REQUEST,
                    createErrorJson("Script is already running"));
        }
        
        try {
            String body = getRequestBody(session);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String script = json.has("script") ? json.get("script").getAsString() : "";
            
            if (script.isEmpty()) {
                return createJsonResponse(Response.Status.BAD_REQUEST,
                        createErrorJson("No script provided"));
            }
            
            // Execute using Rhino
            scriptExecutor.execute(script);
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Script started (Rhino JS)");
            return createJsonResponse(Response.Status.OK, response.toString());
            
        } catch (Exception e) {
            return createJsonResponse(Response.Status.BAD_REQUEST,
                    createErrorJson("Failed to run script: " + e.getMessage()));
        }
    }
    
    /**
     * Handle POST /api/script/stop - Stop running script
     */
    private Response handleScriptStop() {
        if (scriptExecutor == null || !scriptExecutor.isRunning()) {
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "No script running");
            return createJsonResponse(Response.Status.OK, response.toString());
        }
        
        scriptExecutor.stop();
        
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Script stopped");
        return createJsonResponse(Response.Status.OK, response.toString());
    }
    
    /**
     * Handle GET /api/script/status - Get script execution status
     */
    private Response handleScriptStatus() {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        
        if (scriptExecutor != null) {
            response.addProperty("running", scriptExecutor.isRunning());
            response.addProperty("output", scriptExecutor.getOutput());
            String error = scriptExecutor.getLastError();
            if (error != null) {
                response.addProperty("error", error);
            }
        } else {
            response.addProperty("running", false);
            response.addProperty("output", "");
        }
        
        return createJsonResponse(Response.Status.OK, response.toString());
    }
    
    /**
     * Validate IP address format
     */
    private boolean isValidIpAddress(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;
            
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * Broadcast a robot command to WebSocket clients.
     */
    private void broadcastRobotCommand(String command, String param, float value) {
        if (webSocketServer != null) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "robot_command");
            msg.addProperty("command", command);
            msg.addProperty("param", param);
            msg.addProperty("value", value);
            msg.addProperty("timestamp", System.currentTimeMillis());
            webSocketServer.broadcast(msg.toString());
        }
    }
    
    /**
     * Serve static files from assets/web directory.
     */
    private Response serveStaticFile(String uri) {
        // Default to index.html
        if (uri.equals("/")) {
            uri = "/index.html";
        }
        
        // Build asset path
        String assetPath = "web" + uri;
        
        try {
            InputStream inputStream = assetManager.open(assetPath);
            String mimeType = getMimeType(uri);
            
            return newChunkedResponse(Response.Status.OK, mimeType, inputStream);
            
        } catch (IOException e) {
            Log.d(TAG, "File not found: " + assetPath);
            return createJsonResponse(Response.Status.NOT_FOUND, 
                    createErrorJson("File not found: " + uri));
        }
    }
    
    /**
     * Get MIME type for file extension.
     */
    private String getMimeType(String uri) {
        int dotIndex = uri.lastIndexOf('.');
        if (dotIndex > 0) {
            String ext = uri.substring(dotIndex + 1).toLowerCase();
            String mimeType = MIME_TYPES.get(ext);
            if (mimeType != null) {
                return mimeType;
            }
        }
        return "application/octet-stream";
    }
    
    /**
     * Read request body from session.
     */
    private String getRequestBody(IHTTPSession session) throws IOException {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (ResponseException e) {
            throw new IOException("Failed to parse body", e);
        }
        
        String body = files.get("postData");
        return body != null ? body : "";
    }
    
    /**
     * Create JSON error response.
     */
    private String createErrorJson(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        error.addProperty("timestamp", System.currentTimeMillis());
        return error.toString();
    }
    
    /**
     * Create JSON HTTP response with CORS headers.
     */
    private Response createJsonResponse(Response.Status status, String json) {
        Response response = newFixedLengthResponse(status, "application/json", json);
        addCorsHeaders(response);
        return response;
    }
    
    /**
     * Add CORS headers.
     */
    private void addCorsHeaders(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
    }
    
    /**
     * Check if server is online.
     */
    public boolean isOnline() {
        return isOnline;
    }
    
    /**
     * Get WebSocket server for broadcasting.
     */
    public SimpleWebSocketServer getWebSocketServer() {
        return webSocketServer;
    }

    /**
     * Push detection JSON into the scripting environment (if active).
     */
    public void pushDetections(String detectionsJson) {
        if (detectionsJson == null) detectionsJson = "[]";
        if (this.scriptExecutor != null) {
            this.scriptExecutor.pushDetections(detectionsJson);
        }
        // Optionally broadcast detections to websocket clients for debugging
        if (this.webSocketServer != null) {
            try {
                // Simple wrapper message
                com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
                msg.addProperty("type", "detections");
                msg.addProperty("timestamp", System.currentTimeMillis());
                msg.addProperty("detections", detectionsJson);
                this.webSocketServer.broadcast(msg.toString());
            } catch (Exception e) {
                Log.w(TAG, "Failed to broadcast detections: " + e.getMessage());
            }
        }
    }
    
    /**
     * MJPEG stream response - streams video frames as Motion JPEG over HTTP.
     */
    private static class MjpegResponse extends Response {
        public MjpegResponse(VideoStreamServer videoServer) {
            super(Status.OK, "multipart/x-mixed-replace; boundary=boundary123", 
                    new MjpegInputStream(videoServer), -1);
            addHeader("Connection", "keep-alive");
            addHeader("Cache-Control", "no-cache");
            addHeader("Pragma", "no-cache");
        }
    }
    
    /**
     * Simple input stream for MJPEG video data.
     */
    private static class MjpegInputStream extends java.io.InputStream {
        private VideoStreamServer videoServer;
        private java.io.ByteArrayInputStream currentFrameStream;
        private static final byte[] BOUNDARY_FIRST = "--boundary123\r\n".getBytes();
        private static final byte[] BOUNDARY_NEXT = "\r\n--boundary123\r\n".getBytes();
        private static final byte[] CONTENT_TYPE = "Content-Type: image/jpeg\r\n".getBytes();
        private static final byte[] CONTENT_LENGTH_PREFIX = "Content-Length: ".getBytes();
        private static final byte[] CRLF = "\r\n".getBytes();
        private static final byte[] DOUBLE_CRLF = "\r\n\r\n".getBytes();
        
        private int state = 0; // 0=boundary, 1=content-type, 2=content-length, 3=data
        private byte[] currentFrame;
        private byte[] contentLengthBytes;
        private int dataIndex = 0;
        private boolean firstFrame = true;
        private int frameCount = 0;
        
        public MjpegInputStream(VideoStreamServer videoServer) {
            this.videoServer = videoServer;
            this.currentFrame = null;
            Log.i(TAG, "MjpegInputStream created, waiting for frames...");
        }
        
        @Override
        public int read() throws IOException {
            try {
                while (true) {
                    switch (state) {
                        case 0: // Send boundary
                            if (currentFrame == null) {
                                currentFrame = videoServer.getNextFrame(500);
                                if (currentFrame == null) {
                                    if (frameCount == 0) {
                                        Log.w(TAG, "Waiting for first frame... (queue size: " + videoServer.getQueueSize() + ")");
                                    }
                                    Thread.sleep(100);
                                    continue;
                                }
                                frameCount++;
                                if (frameCount <= 3 || frameCount % 30 == 0) {
                                    Log.i(TAG, "Streaming frame #" + frameCount + " (size: " + currentFrame.length + " bytes)");
                                }
                                dataIndex = 0;
                            }
                            
                            byte[] boundary = firstFrame ? BOUNDARY_FIRST : BOUNDARY_NEXT;
                            if (dataIndex < boundary.length) {
                                return boundary[dataIndex++] & 0xFF;
                            }
                            firstFrame = false;
                            dataIndex = 0;
                            state = 1;
                            break;
                            
                        case 1: // Send Content-Type header
                            if (dataIndex < CONTENT_TYPE.length) {
                                return CONTENT_TYPE[dataIndex++] & 0xFF;
                            }
                            dataIndex = 0;
                            state = 2;
                            break;
                            
                        case 2: // Send Content-Length header
                            if (contentLengthBytes == null) {
                                String lengthStr = String.valueOf(currentFrame.length);
                                contentLengthBytes = lengthStr.getBytes();
                            }
                            
                            if (dataIndex < CONTENT_LENGTH_PREFIX.length) {
                                return CONTENT_LENGTH_PREFIX[dataIndex++] & 0xFF;
                            } else if (dataIndex < CONTENT_LENGTH_PREFIX.length + contentLengthBytes.length) {
                                return contentLengthBytes[dataIndex++ - CONTENT_LENGTH_PREFIX.length] & 0xFF;
                            } else if (dataIndex < CONTENT_LENGTH_PREFIX.length + contentLengthBytes.length + DOUBLE_CRLF.length) {
                                return DOUBLE_CRLF[dataIndex++ - CONTENT_LENGTH_PREFIX.length - contentLengthBytes.length] & 0xFF;
                            }
                            dataIndex = 0;
                            state = 3;
                            break;
                            
                        case 3: // Send JPEG data
                            if (dataIndex < currentFrame.length) {
                                return currentFrame[dataIndex++] & 0xFF;
                            }
                            // Reset for next frame
                            dataIndex = 0;
                            state = 0;
                            currentFrame = null;
                            contentLengthBytes = null;
                            break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Stream interrupted");
            }
        }
    }
}