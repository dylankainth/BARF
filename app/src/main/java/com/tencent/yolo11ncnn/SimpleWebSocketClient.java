/**
 * SimpleWebSocketClient.java
 * 
 * WebSocket client that connects to ESP32 server for robot communication.
 */
package com.tencent.yolo11ncnn;

import android.util.Log;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;

public class SimpleWebSocketClient extends WebSocketClient {
    private static final String TAG = "SimpleWebSocketClient";
    private RhinoScriptExecutor scriptExecutor;
    
    public SimpleWebSocketClient(String host, int port, RhinoScriptExecutor scriptExecutor) throws URISyntaxException {
        super(new URI("ws://" + host + ":" + port + "/"));
        this.scriptExecutor = scriptExecutor;
        Log.d(TAG, "[INIT] WebSocket client initialized for " + host + ":" + port);
    }
    
    @Override
    public void onOpen(ServerHandshake handshake) {
        Log.i(TAG, "[CONNECTED] Connected to ESP32 server");
        sendMessage("android:connected");
    }
    
    @Override
    public void onMessage(String message) {
        Log.d(TAG, "[MESSAGE_RECEIVED] " + message);
        
        // Check for button events from ESP32
        if (message.startsWith("button:")) {
            String[] parts = message.split(":");
            if (parts.length == 2) {
                String eventType = parts[1].trim();
                Log.i(TAG, "[BUTTON_EVENT] Button " + eventType + " received from ESP32");
                
                if ("pressed".equalsIgnoreCase(eventType)) {
                    if (scriptExecutor != null) {
                        Log.d(TAG, "[BUTTON_CALLBACK] Calling onButtonPressed");
                        scriptExecutor.onButtonPressed();
                    }
                } else if ("released".equalsIgnoreCase(eventType)) {
                    if (scriptExecutor != null) {
                        Log.d(TAG, "[BUTTON_CALLBACK] Calling onButtonReleased");
                        scriptExecutor.onButtonReleased();
                    }
                }
            }
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        Log.i(TAG, "[DISCONNECTED] Disconnected from ESP32 (code: " + code + ", reason: " + reason + ", remote: " + remote + ")");
    }
    
    @Override
    public void onError(Exception ex) {
        Log.e(TAG, "[ERROR] WebSocket error: " + ex.getMessage(), ex);
    }
    
    /**
     * Send a command message to ESP32.
     */
    public void sendCommand(int x, int y, int r, int e) {
        String message = x + "," + y + "," + r + "," + e;
        sendMessage(message);
    }
    
    /**
     * Send a message to ESP32.
     */
    public void sendMessage(String message) {
        try {
            if (isOpen()) {
                send(message);
                Log.d(TAG, "[SEND] Sent to ESP32: " + message);
            } else {
                Log.w(TAG, "[SEND_ERROR] WebSocket not connected, cannot send: " + message);
            }
        } catch (Exception e) {
            Log.e(TAG, "[SEND_ERROR] Failed to send message: " + e.getMessage(), e);
        }
    }
}
