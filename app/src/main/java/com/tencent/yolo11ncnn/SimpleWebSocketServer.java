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
    
    public SimpleWebSocketServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        Log.i(TAG, "Simple WebSocket server created on port " + port);
    }
    
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        String clientId = conn.getRemoteSocketAddress().toString();
        Log.i(TAG, "Client connected: " + clientId + " (total: " + clients.size() + ")");
        
        // Send welcome message
        try {
            JsonObject welcome = new JsonObject();
            welcome.addProperty("type", "welcome");
            welcome.addProperty("message", "Connected to Android WebSocket server");
            welcome.addProperty("clientId", clientId);
            welcome.addProperty("timestamp", System.currentTimeMillis());
            
            conn.send(welcome.toString());
            Log.d(TAG, "Welcome message sent to: " + clientId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send welcome message: " + e.getMessage());
        }
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        String clientId = conn.getRemoteSocketAddress().toString();
        Log.i(TAG, "Client disconnected: " + clientId + " (code: " + code + ", reason: " + reason + ", total: " + clients.size() + ")");
    }
    
    @Override
    public void onMessage(WebSocket conn, String message) {
        String clientId = conn.getRemoteSocketAddress().toString();
        Log.d(TAG, "Received from " + clientId + ": " + message);
        
        // Echo message back to sender
        try {
            JsonObject response = new JsonObject();
            response.addProperty("type", "echo");
            response.addProperty("originalMessage", message);
            response.addProperty("timestamp", System.currentTimeMillis());
            response.addProperty("from", "server");
            
            conn.send(response.toString());
            Log.d(TAG, "Echo sent to: " + clientId);
            
            // Also broadcast to all other clients
            broadcastToOthers(conn, message, clientId);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to process message: " + e.getMessage());
        }
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        String clientId = conn != null ? conn.getRemoteSocketAddress().toString() : "unknown";
        Log.e(TAG, "WebSocket error for " + clientId + ": " + ex.getMessage(), ex);
        if (conn != null) {
            clients.remove(conn);
        }
    }
    
    @Override
    public void onStart() {
        Log.i(TAG, "Simple WebSocket server started successfully");
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
        
        for (WebSocket client : clients) {
            if (client != sender && client.isOpen()) {
                try {
                    client.send(broadcastJson);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to broadcast to client: " + e.getMessage());
                    clients.remove(client);
                }
            }
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
        Log.d(TAG, "Broadcasting to " + clients.size() + " clients: " + message);
        
        for (WebSocket client : clients) {
            try {
                if (client.isOpen()) {
                    client.send(json);
                } else {
                    clients.remove(client);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send broadcast: " + e.getMessage());
                clients.remove(client);
            }
        }
    }
    
    /**
     * Get the number of connected clients.
     */
    public int getClientCount() {
        return clients.size();
    }
    
    /**
     * Close all client connections and stop the server.
     */
    public void shutdown() {
        try {
            Log.i(TAG, "Shutting down WebSocket server...");
            stop(1000);
        } catch (InterruptedException e) {
            Log.e(TAG, "Error stopping WebSocket server: " + e.getMessage());
        }
    }
}