package com.chat;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.eclipse.jetty.websocket.api.Session;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Main {

    private static final String DB_URL = "jdbc:sqlite:chat_app.db";
    private static final Gson gson = new Gson();

    // Core Active Memory Repositories
    private static final Map<String, Session> wsConnections = new ConcurrentHashMap<>();
    private static final Map<Session, String> inverseConnections = new ConcurrentHashMap<>();
    private static final Set<String> establishedConnections = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> activeInvites = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> chatHistories = new ConcurrentHashMap<>();
    private static final Map<String, Integer> unreadCounts = new ConcurrentHashMap<>();
    private static final Map<String, Long> userLastSeen = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        initializeDatabase();

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
        }).start(7070);

        // Standard HTTP API REST Endpoints
        app.post("/api/login", ctx -> {
            JsonObject body = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String username = body.get("username").getAsString().trim().toLowerCase();
            
            if (username.isEmpty()) {
                ctx.status(400).result("Username cannot be empty");
                return;
            }
            
            userLastSeen.put(username, System.currentTimeMillis());
            JsonObject response = new JsonObject();
            response.addProperty("status", "SUCCESS");
            response.addProperty("username", username);
            ctx.json(gson.toJson(response));
        });

        app.get("/api/online-status", ctx -> {
            String targetUser = ctx.queryParam("user");
            JsonObject response = new JsonObject();
            if (targetUser != null) {
                long currentTime = System.currentTimeMillis();
                long lastSeen = userLastSeen.getOrDefault(targetUser.toLowerCase(), 0L);
                boolean isOnline = (currentTime - lastSeen) < 7000; 
                response.addProperty("username", targetUser);
                response.addProperty("status", isOnline ? "ONLINE" : "OFFLINE");
            } else {
                response.addProperty("error", "Missing user parameter");
            }
            ctx.json(gson.toJson(response));
        });

        // --- WebSocket Pipeline Engine ---
        app.ws("/ws", ws -> {
            
            ws.onConnect(ctx -> {
                // Connection initiated
                System.out.println("[WebSocket] Transient socket pipe established: " + ctx.session.getRemoteAddress());
            });

            ws.onClose(ctx -> {
                String username = inverseConnections.remove(ctx.session);
                if (username != null) {
                    wsConnections.remove(username);
                    System.out.println("[WebSocket] Session closed for user: " + username);
                }
            });

            ws.onError(ctx -> {
                String username = inverseConnections.get(ctx.session);
                System.err.println("[WebSocket] Pipe error encountered for target: " + (username != null ? username : "Unknown"));
            });

            ws.onMessage(ctx -> {
                String messageStr = ctx.message();
                JsonObject json = JsonParser.parseString(messageStr).getAsJsonObject();
                String type = json.get("type").getAsString();

                // 1. Identify Connection Handshake Registration Event
                if ("REGISTER".equals(type)) {
                    String username = json.get("username").getAsString().trim().toLowerCase();
                    wsConnections.put(username, ctx.session);
                    inverseConnections.put(ctx.session, username);
                    userLastSeen.put(username, System.currentTimeMillis());
                    System.out.println("[WebSocket] User registered stream: " + username);
                    return;
                }

                // Heartbeat to sustain long poll threads
                if ("HEARTBEAT".equals(type)) {
                    String username = json.get("username").getAsString().trim().toLowerCase();
                    userLastSeen.put(username, System.currentTimeMillis());
                    return;
                }

                // 2. Client Invitation Sequence
                if ("INVITE".equals(type)) {
                    String sender = json.get("sender").getAsString().trim().toLowerCase();
                    String receiver = json.get("receiver").getAsString().trim().toLowerCase();
                    String roomKey = generateRoomKey(sender, receiver);

                    activeInvites.put(roomKey, sender);
                    
                    Session receiverSession = wsConnections.get(receiver);
                    if (receiverSession != null && receiverSession.isOpen()) {
                        JsonObject invitePkg = new JsonObject();
                        invitePkg.addProperty("type", "INVITE");
                        invitePkg.addProperty("sender", sender);
                        invitePkg.addProperty("room", roomKey);
                        receiverSession.getRemote().sendString(gson.toJson(invitePkg));
                    }
                } 
                
                // 3. Invitation Acceptance Phase
                else if ("INVITE_ACCEPTED".equals(type)) {
                    String sender = json.get("sender").getAsString().trim().toLowerCase(); // Person who accepts
                    String receiver = json.get("receiver").getAsString().trim().toLowerCase(); // Original inviter
                    String roomKey = json.get("room").getAsString();

                    saveConnectionToDatabase(sender, receiver);
                    establishedConnections.add(roomKey);

                    JsonObject acceptNotice = new JsonObject();
                    acceptNotice.addProperty("type", "INVITE_ACCEPTED");
                    acceptNotice.addProperty("room", roomKey);

                    Session s1 = wsConnections.get(sender);
                    Session s2 = wsConnections.get(receiver);
                    if (s1 != null && s1.isOpen()) s1.getRemote().sendString(gson.toJson(acceptNotice));
                    if (s2 != null && s2.isOpen()) s2.getRemote().sendString(gson.toJson(acceptNotice));
                } 

                // 4. Message Transmission Router (Text & Media Packets)
                else if ("TEXT".equals(type) || "MEDIA".equals(type)) {
                    String room = json.get("room").getAsString();
                    String sender = json.get("sender").getAsString().trim().toLowerCase();
                    String receiver = json.get("receiver").getAsString().trim().toLowerCase();

                    // 🔥 CRITICAL NETWORK-FAILSAFE BUGFIX:
                    // Validate connection state against SQLite database if memory set is cleared during IP transitions
                    boolean isConnected = establishedConnections.contains(room);
                    if (!isConnected) {
                        isConnected = checkConnectionInDatabase(sender, receiver);
                        if (isConnected) {
                            establishedConnections.add(room); // Instantly restore to active memory mapping cache
                        }
                    }

                    // If neither memory cache nor SQLite database has verified this relation, intercept and trigger Handshake verification
                    if (!isConnected) {
                        JsonObject alert = new JsonObject();
                        alert.addProperty("type", "INVITE");
                        alert.addProperty("sender", sender);
                        alert.addProperty("room", room);
                        
                        Session receiverSession = wsConnections.get(receiver);
                        if (receiverSession != null && receiverSession.isOpen()) {
                            receiverSession.getRemote().sendString(gson.toJson(alert));
                        }
                        return; // Terminate execution block to halt unverified broadcast
                    }

                    // Process structural storage
                    saveMessageToDatabase(room, messageStr);
                    chatHistories.computeIfAbsent(room, k -> new CopyOnWriteArrayList<>()).add(messageStr);

                    Session s1 = wsConnections.get(sender);
                    Session s2 = wsConnections.get(receiver);

                    if (s2 != null && s2.isOpen()) {
                        json.addProperty("status", "DELIVERED");
                        s2.getRemote().sendString(gson.toJson(json));
                        
                        if (s1 != null && s1.isOpen()) {
                            JsonObject delivNotice = new JsonObject();
                            delivNotice.addProperty("type", "STATUS_UPDATE");
                            delivNotice.addProperty("room", room);
                            delivNotice.addProperty("sender", receiver);
                            delivNotice.addProperty("status", "DELIVERED");
                            s1.getRemote().sendString(gson.toJson(delivNotice));
                        }
                    } else {
                        json.addProperty("status", "SENT");
                        if (s1 != null && s1.isOpen()) {
                            s1.getRemote().sendString(gson.toJson(json));
                        }
                        
                        String unreadKey = receiver + "_" + sender;
                        unreadCounts.put(unreadKey, unreadCounts.getOrDefault(unreadKey, 0) + 1);
                        sendWebPushNotification(receiver, capitalizeFirstLetter(sender), "Sent you an encrypted package.");
                    }
                }
                
                // 5. Query Active Chat Threads History
                else if ("HISTORY_REQUEST".equals(type)) {
                    String room = json.get("room").getAsString();
                    List<String> history = chatHistories.getOrDefault(room, new ArrayList<>());
                    
                    JsonObject historyPkg = new JsonObject();
                    historyPkg.addProperty("type", "HISTORY_RESPONSE");
                    historyPkg.addProperty("room", room);
                    historyPkg.add("messages", gson.toJsonTree(history));
                    
                    ctx.session.getRemote().sendString(gson.toJson(historyPkg));
                }

                // 6. User State Indicators
                else if ("TYPING".equals(type)) {
                    String receiver = json.get("receiver").getAsString().trim().toLowerCase();
                    Session receiverSession = wsConnections.get(receiver);
                    if (receiverSession != null && receiverSession.isOpen()) {
                        receiverSession.getRemote().sendString(gson.toJson(json));
                    }
                }
            });
        });
    }

    // --- Helper Functions & Database Utilities ---

    private static String generateRoomKey(String user1, String user2) {
        String[] arr = {user1.toLowerCase(), user2.toLowerCase()};
        Arrays.sort(arr);
        return arr[0] + "_" + arr[1];
    }

    private static String capitalizeFirstLetter(String original) {
        if (original == null || original.isEmpty()) return original;
        return original.substring(0, 1).toUpperCase() + original.substring(1);
    }

    private static void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            // Core connections matrix table mapping authorized peer handshakes
            stmt.execute("CREATE TABLE IF NOT EXISTS connections (" +
                    "user1 TEXT, " +
                    "user2 TEXT, " +
                    "PRIMARY KEY(user1, user2))");

            // Transaction records processing table log
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "room TEXT, " +
                    "payload TEXT, " +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP)");
            
            System.out.println("[Database] Core engine tables verified/initialized.");
        } catch (SQLException e) {
            System.err.println("[Database Initialization Error] " + e.getMessage());
        }
    }

    private static void saveConnectionToDatabase(String u1, String u2) {
        String user1 = u1.toLowerCase();
        String user2 = u2.toLowerCase();
        if (user1.compareTo(user2) > 0) {
            String temp = user1; user1 = user2; user2 = temp;
        }

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR IGNORE INTO connections (user1, user2) VALUES (?, ?)")) {
            ps.setString(1, user1);
            ps.setString(2, user2);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[Database Error] Saving relationship context failed: " + e.getMessage());
        }
    }

    private static boolean checkConnectionInDatabase(String u1, String u2) {
        String user1 = u1.toLowerCase();
        String user2 = u2.toLowerCase();
        if (user1.compareTo(user2) > 0) {
            String temp = user1; user1 = user2; user2 = temp;
        }

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM connections WHERE user1 = ? AND user2 = ?")) {
            ps.setString(1, user1);
            ps.setString(2, user2);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next(); // Returns true if a historical valid handshake exists
            }
        } catch (SQLException e) {
            System.err.println("[Database Error] Connection check fallback routine errored: " + e.getMessage());
            return false;
        }
    }

    private static void saveMessageToDatabase(String room, String payload) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO messages (room, payload) VALUES (?, ?)")) {
            ps.setString(1, room);
            ps.setString(2, payload);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[Database Error] Storing transmission log failed: " + e.getMessage());
        }
    }

    private static void sendWebPushNotification(String recipient, String title, String body) {
        // Log target mock method hook for Web Push Notification subsystem payloads
        System.out.println("[Web Push Trigger] Destination: " + recipient + " | Head: " + title + " | Body: " + body);
    }
}
