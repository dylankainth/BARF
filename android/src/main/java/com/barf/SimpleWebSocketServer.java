/**
 * SimpleWebSocketServer.java
 * 
 * Minimal WebSocket server using Java-WebSocket for basic message testing.
 */
package com.barf;

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

    public interface ConnectionListener {
        void onDesktopConnected();
        void onDesktopDisconnected();
    }

    // Thread-safe set of connected clients
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ConnectionListener connectionListener;

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }
    
    public SimpleWebSocketServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        Log.i(TAG, "Simple WebSocket server created on port " + port);
    }
    
    /** Called with the raw WebSocket connection whenever a new client joins. */
    public interface NewClientListener {
        void onNewClient(WebSocket conn);
    }

    private NewClientListener newClientListener;

    public void setNewClientListener(NewClientListener listener) {
        this.newClientListener = listener;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        String clientId = conn.getRemoteSocketAddress().toString();
        Log.i(TAG, "Client connected: " + clientId + " (total: " + clients.size() + ")");

        if (connectionListener != null) {
            connectionListener.onDesktopConnected();
        }

        // Let PhoneApiServer push current state (serial status, etc.) to the new client
        if (newClientListener != null) {
            newClientListener.onNewClient(conn);
        }
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        boolean wasPresent = clients.remove(conn);
        String clientId = conn.getRemoteSocketAddress().toString();
        Log.i(TAG, "Client disconnected: " + clientId + " (code: " + code + ", reason: " + reason + ", total: " + clients.size() + ")");

        // Only fire if we actually removed this client (prevents double-fire when onError
        // already removed it before onClose is called for the same connection).
        if (wasPresent && clients.isEmpty() && connectionListener != null) {
            connectionListener.onDesktopDisconnected();
        }
    }
    
    /** Listener for incoming messages that need to be handled by the app (e.g. serial write). */
    public interface MessageListener {
        void onMessage(String message);
    }

    private MessageListener messageListener;

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Log.d(TAG, "Received from " + conn.getRemoteSocketAddress() + ": " + message);
        if (messageListener != null) {
            messageListener.onMessage(message);
        }
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        String clientId = conn != null ? conn.getRemoteSocketAddress().toString() : "unknown";
        Log.e(TAG, "WebSocket error for " + clientId + ": " + ex.getMessage(), ex);
        if (conn != null) {
            // onClose will follow this for the same connection, so don't fire the listener
            // here — let onClose handle it via the wasPresent guard to avoid double-fire.
            clients.remove(conn);
        }
    }
    
    @Override
    public void onStart() {
        Log.i(TAG, "Simple WebSocket server started successfully");
    }
    
    /**
     * Broadcast a message to all connected clients as-is (no extra wrapping).
     */
    public void broadcast(String message) {
        Log.d(TAG, "Broadcasting to " + clients.size() + " clients: " + message);
        for (WebSocket client : clients) {
            try {
                if (client.isOpen()) {
                    client.send(message);
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