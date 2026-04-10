package com.fiji.mcp.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.IJ;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BridgeWebSocketServer extends WebSocketServer {

    private final RequestHandler handler;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public BridgeWebSocketServer(int port, RequestHandler handler) {
        super(new InetSocketAddress(port));
        this.handler = handler;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        IJ.log("[fiji-mcp] Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        IJ.log("[fiji-mcp] Client disconnected");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        executor.submit(() -> {
            String id = null;
            try {
                JsonObject request = JsonParser.parseString(message).getAsJsonObject();
                id = request.get("id").getAsString();
                String action = request.get("action").getAsString();
                JsonObject params = request.has("params")
                        ? request.getAsJsonObject("params")
                        : new JsonObject();

                JsonObject result = handler.handle(action, params);
                sendResponse(conn, id, result);
            } catch (Exception e) {
                if (id != null) {
                    sendError(conn, id, e.getMessage(), e.getClass().getSimpleName());
                } else {
                    IJ.log("[fiji-mcp] Bad request: " + e.getMessage());
                }
            }
        });
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        IJ.log("[fiji-mcp] WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        IJ.log("[fiji-mcp] WebSocket server started on port " + getPort());
    }

    /** Send a JSON event to all connected clients. */
    public void broadcastEvent(String eventJson) {
        broadcast(eventJson);
    }

    private void sendResponse(WebSocket conn, String id, JsonObject result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("id", id);
        resp.addProperty("type", "response");
        resp.addProperty("status", "ok");
        resp.add("result", result);
        conn.send(resp.toString());
    }

    private void sendError(WebSocket conn, String id, String message, String type) {
        JsonObject resp = new JsonObject();
        resp.addProperty("id", id);
        resp.addProperty("type", "response");
        resp.addProperty("status", "error");
        JsonObject error = new JsonObject();
        error.addProperty("message", message);
        error.addProperty("type", type);
        resp.add("error", error);
        conn.send(resp.toString());
    }
}
