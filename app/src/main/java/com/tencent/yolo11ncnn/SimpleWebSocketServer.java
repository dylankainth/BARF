/**
 * SimpleWebSocketServer.java
 * 
 * Minimal WebSocket server using Java-WebSocket for basic message testing.
 */
package com.tencent.yolo11ncnn;

import android.util.Log;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleWebSocketServer extends WebSocketServer {
    private static final String TAG = "SimpleWebSocketServer";
    
    // Thread-safe set of connected clients
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Button event callback interface
    public interface ButtonEventCallback {
        void onButtonPressed();
        void onButtonReleased();
    }
    
    private ButtonEventCallback buttonCallback;
    
        public SimpleWebSocketServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        Log.i(TAG, "[INIT] WebSocket server created on port " + port);
    }
    
    /**
     * Set the button event callback.
     */
    public void setButtonEventCallback(ButtonEventCallback callback) {
        this.buttonCallback = callback;
        Log.d(TAG, "[CONFIG] Button event callback set");
    }
    
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        String clientId = conn.getRemoteSocketAddress().toString();
        Log.i(TAG, "[CONNECT] Client connected: " + clientId + " | Total clients: " + clients.size());
        Log.d(TAG, "[CONNECT_DEBUG] Protocol: " + (handshake != null ? handshake.getResourceDescriptor() : "unknown"));
        
        // Send welcome message
        try {
            JsonObject welcome = new JsonObject();
            welcome.addProperty("type", "welcome");
            welcome.addProperty("message", "Connected to Android WebSocket server");
            welcome.addProperty("clientId", clientId);
            welcome.addProperty("timestamp", System.currentTimeMillis());
            
            conn.send(welcome.toString());
            Log.d(TAG, "[WELCOME] Welcome message sent to: " + clientId);
        } catch (Exception e) {
            Log.e(TAG, "[WELCOME_ERROR] Failed to send welcome message to " + clientId + ": " + e.getMessage(), e);
        }
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        String clientId = conn.getRemoteSocketAddress().toString();
        Log.i(TAG, "[DISCONNECT] Client disconnected: " + clientId + " | Code: " + code + " | Reason: " + reason + " | Remote: " + remote + " | Remaining clients: " + clients.size());
    }
    
    @Override
    public void onMessage(WebSocket conn, String message) {
        String clientId = conn.getRemoteSocketAddress().toString();
        Log.d(TAG, "[MESSAGE] Received from " + clientId + " (" + clients.size() + " clients connected): " + message);
        
        // Check for button events from ESP32
        if (message.startsWith("button:")) {
            Log.d(TAG, "[BUTTON] Button event detected: " + message);
            handleButtonEvent(message);
            return;
        }
        
        // Echo message back to sender
        try {
            JsonObject response = new JsonObject();
            response.addProperty("type", "echo");
            response.addProperty("originalMessage", message);
            response.addProperty("timestamp", System.currentTimeMillis());
            response.addProperty("from", "server");
            
            conn.send(response.toString());
            Log.d(TAG, "[ECHO] Echo response sent to: " + clientId);
            
            // Also broadcast to all other clients
            broadcastToOthers(conn, message, clientId);
            
        } catch (Exception e) {
            Log.e(TAG, "[ERROR] Failed to process message from " + clientId + ": " + e.getMessage(), e);
        }
    }
    
    /**
     * Handle button events from ESP32.
     */
    private void handleButtonEvent(String message) {
        // Format: button:pressed or button:released
        String[] parts = message.split(":");
        if (parts.length == 2) {
            String eventType = parts[1].trim();
            Log.i(TAG, "[BUTTON_EVENT] Button " + eventType + " event received from ESP32");
            
            if (buttonCallback != null) {
                if ("pressed".equalsIgnoreCase(eventType)) {
                    try {
                        Log.d(TAG, "[BUTTON_CALLBACK] Invoking onButtonPressed callback");
                        buttonCallback.onButtonPressed();
                        Log.d(TAG, "[BUTTON_CALLBACK] onButtonPressed completed");
                    } catch (Exception e) {
                        Log.e(TAG, "[BUTTON_ERROR] Error in onButtonPressed callback: " + e.getMessage(), e);
                    }
                } else if ("released".equalsIgnoreCase(eventType)) {
                    try {
                        Log.d(TAG, "[BUTTON_CALLBACK] Invoking onButtonReleased callback");
                        buttonCallback.onButtonReleased();
                        Log.d(TAG, "[BUTTON_CALLBACK] onButtonReleased completed");
                    } catch (Exception e) {
                        Log.e(TAG, "[BUTTON_ERROR] Error in onButtonReleased callback: " + e.getMessage(), e);
                    }
                }
            } else {
                Log.w(TAG, "[BUTTON_WARNING] Button callback not set, ignoring button event");
            }
        } else {
            Log.w(TAG, "[BUTTON_WARNING] Invalid button event format: " + message);
        }
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        String clientId = conn != null ? conn.getRemoteSocketAddress().toString() : "unknown";
        Log.e(TAG, "[WS_ERROR] WebSocket error for " + clientId + ": " + ex.getMessage(), ex);
        if (conn != null) {
            Log.d(TAG, "[ERROR_CLEANUP] Removing client " + clientId + " due to error");
            clients.remove(conn);
        }
    }
    
    @Override
    public void onStart() {
        Log.i(TAG, "[START] WebSocket server started successfully on port " + getPort());
    }
    
    /**
     * Broadcast a message to all clients except the sender.
     */
    private void broadcastToOthers(WebSocket sender, String originalMessage, String senderId) {
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "broadcast");
        broadcast.addProperty("message", originalMessage);
        broadcast.addProperty("from", senderId);
        broadcast.addProperty("timestamp", System.currentTimeMillis());
        
        String broadcastJson = broadcast.toString();
        int broadcastCount = 0;
        
        for (WebSocket client : clients) {
            if (client != sender && client.isOpen()) {
                try {
                    client.send(broadcastJson);
                    broadcastCount++;
                } catch (Exception e) {
                    Log.e(TAG, "[BROADCAST_ERROR] Failed to broadcast to " + client.getRemoteSocketAddress() + ": " + e.getMessage());
                    clients.remove(client);
                }
            }
        }
        
        if (broadcastCount > 0) {
            Log.d(TAG, "[BROADCAST] Message from " + senderId + " broadcasted to " + broadcastCount + " other client(s)");
        }
    }
    
    /**
     * Broadcast a message to all connected clients.
     */
    public void broadcast(String message) {
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "server_broadcast");
        broadcast.addProperty("message", message);
        broadcast.addProperty("timestamp", System.currentTimeMillis());
        
        String json = broadcast.toString();
        int sentCount = 0;
        int failedCount = 0;
        Log.d(TAG, "[BROADCAST] Attempting to broadcast to " + clients.size() + " client(s): " + message);
        
        for (WebSocket client : clients) {
            try {
                if (client.isOpen()) {
                    client.send(json);
                    sentCount++;
                    Log.d(TAG, "[BROADCAST_SENT] Message sent to " + client.getRemoteSocketAddress());
                } else {
                    Log.d(TAG, "[BROADCAST_SKIP] Client " + client.getRemoteSocketAddress() + " connection not open");
                    clients.remove(client);
                }
            } catch (Exception e) {
                failedCount++;
                Log.e(TAG, "[BROADCAST_ERROR] Failed to send broadcast to " + client.getRemoteSocketAddress() + ": " + e.getMessage());
                clients.remove(client);
            }
        }
        
        Log.i(TAG, "[BROADCAST_COMPLETE] Broadcast: " + sentCount + " sent, " + failedCount + " failed");
    }
    
    /**
     * Get the number of connected clients.
     */
    public int getClientCount() {
        int count = clients.size();
        Log.d(TAG, "[STATUS] Current connected clients: " + count);
        return count;
    }
    
    /**
     * Close all client connections and stop the server.
     */
    public void shutdown() {
        try {
            Log.i(TAG, "[SHUTDOWN] Shutting down WebSocket server... (" + clients.size() + " clients connected)");
            stop(1000);
            Log.i(TAG, "[SHUTDOWN] WebSocket server stopped");
        } catch (InterruptedException e) {
            Log.e(TAG, "[SHUTDOWN_ERROR] Error stopping WebSocket server: " + e.getMessage(), e);
        }
    }
}