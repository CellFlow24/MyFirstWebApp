package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

// 🔔 IMPORTS FOR WEB PUSH
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import com.google.gson.Gson;
import java.security.Security;
import org.apache.http.HttpResponse;

public class Main {

    public static ConcurrentHashMap<String, String> userProfilePics = new ConcurrentHashMap<>();
    
    // 🔔 Stores the unique phone routing tokens for each user
    public static ConcurrentHashMap<String, String> userSubscriptions = new ConcurrentHashMap<>();
    
    private static final String DB_URL = "jdbc:sqlite:/app/data/chatlounge.db";
    private static PushService pushService;

    public static void main(String[] args) {
        HashMap<String, String> userDatabase = new HashMap<>();
        HashMap<String, Long> userLastSeen = new HashMap<>();
        HashMap<String, String> activeInvites = new HashMap<>();
        HashSet<String> establishedConnections = new HashSet<>();
        HashMap<String, Integer> unreadCounts = new HashMap<>();
        HashMap<String, ArrayList<String>> chatHistories = new HashMap<>();

        // 🔔 Initialize Security Provider and Web Push Engine with your Keys
        Security.addProvider(new BouncyCastleProvider());
        try {
            pushService = new PushService(
                "BFlvSgA-vI6tX7U-tG9uY8zK5mN_6pQ4rS3tV2uW1xY_z_VAPID_PUBLIC_KEY_HERE",
                "v_VAPID_PRIVATE_KEY_HERE",
                "mailto:admin@dipsum.chat"
            );
        } catch (Exception e) {
            System.err.println("Failed to initialize Web Push Engine: " + e.getMessage());
        }

        initializeDatabase(userDatabase, establishedConnections, chatHistories);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
        });

        // Redirects root to your dashboard
        app.get("/", ctx -> ctx.redirect("/index.html"));

        // --- API CHANNELS ---

        app.get("/api/login", ctx -> {
            String u = ctx.queryParam("user");
            String p = ctx.queryParam("pass");
            if (u == null || p == null) {
                ctx.result("fail");
                return;
            }
            u = u.trim().toLowerCase();
            if (userDatabase.containsKey(u) && userDatabase.get(u).equals(p)) {
                userLastSeen.put(u, System.currentTimeMillis());
                ctx.result("success");
            } else {
                ctx.result("fail");
            }
        });

        app.get("/api/register", ctx -> {
            String u = ctx.queryParam("user");
            String p = ctx.queryParam("pass");
            if (u == null || p == null || u.trim().isEmpty() || p.trim().isEmpty()) {
                ctx.result("fail");
                return;
            }
            u = u.trim().toLowerCase();
            if (userDatabase.containsKey(u)) {
                ctx.result("exists");
            } else {
                userDatabase.put(u, p);
                saveUserToDatabase(u, p);
                userLastSeen.put(u, System.currentTimeMillis());
                ctx.result("success");
            }
        });

        app.get("/api/online", ctx -> {
            String u = ctx.queryParam("user");
            if (u == null) { ctx.result("offline"); return; }
            u = u.trim().toLowerCase();
            long now = System.currentTimeMillis();
            long last = userLastSeen.getOrDefault(u, 0L);
            if (now - last < 7000) {
                ctx.result("online");
            } else {
                ctx.result("offline");
            }
        });

        app.get("/api/profile-pic", ctx -> {
            String u = ctx.queryParam("user");
            if (u == null) { ctx.result(""); return; }
            ctx.result(userProfilePics.getOrDefault(u.trim().toLowerCase(), ""));
        });

        app.post("/api/profile-pic", ctx -> {
            String u = ctx.queryParam("user");
            String base64 = ctx.body();
            if (u != null && base64 != null) {
                u = u.trim().toLowerCase();
                userProfilePics.put(u, base64);
                saveProfilePicToDatabase(u, base64);
                ctx.result("saved");
            } else {
                ctx.result("fail");
            }
        });

        // 🔔 Receive and store browser PWA subscription contexts
        app.post("/api/saveSubscription", ctx -> {
            String username = ctx.queryParam("username");
            String subscriptionJson = ctx.body();
            if (username != null && subscriptionJson != null && !subscriptionJson.trim().isEmpty()) {
                username = username.trim().toLowerCase();
                userSubscriptions.put(username, subscriptionJson);
                saveSubscriptionToDatabase(username, subscriptionJson);
                ctx.result("Subscription paired on server.");
            } else {
                ctx.status(400).result("Invalid subscription request assets.");
            }
        });

        // --- WEB SOCKET ROUTING PIPELINE ---

        app.ws("/ws", ws -> {
            // 🛠️ FIX: Explicitly use WsContext for all collections to guarantee type compatibility
            ConcurrentHashMap<String, io.javalin.websocket.WsContext> activeSockets = new ConcurrentHashMap<>();
            ConcurrentHashMap<io.javalin.websocket.WsContext, String> socketOwners = new ConcurrentHashMap<>();

            ws.onConnect(ctx -> {
                // Connection initiated cleanly
            });

            ws.onClose(ctx -> {
                String u = socketOwners.remove(ctx);
                if (u != null) activeSockets.remove(u);
            });

            ws.onMessage(ctx -> {
                String text = ctx.message();
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                String type = json.get("type").getAsString();

                if ("REGISTER".equals(type)) {
                    String u = json.get("username").getAsString().trim().toLowerCase();
                    activeSockets.put(u, ctx);
                    socketOwners.put(ctx, u);
                    userLastSeen.put(u, System.currentTimeMillis());
                    
                    int totalUnread = 0;
                    for (String key : unreadCounts.keySet()) {
                        if (key.startsWith(u + "_")) {
                            totalUnread += unreadCounts.get(key);
                        }
                    }
                    com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
                    resp.addProperty("type", "UNREAD_TOTAL");
                    resp.addProperty("count", totalUnread);
                    ctx.send(resp.toString());
                    return;
                }

                if ("HEARTBEAT".equals(type)) {
                    String u = json.get("username").getAsString().trim().toLowerCase();
                    userLastSeen.put(u, System.currentTimeMillis());
                    return;
                }

                if ("INVITE".equals(type)) {
                    String s = json.get("sender").getAsString().trim().toLowerCase();
                    String r = json.get("receiver").getAsString().trim().toLowerCase();
                    String room = json.get("room").getAsString();
                    activeInvites.put(room, s);

                    io.javalin.websocket.WsContext rCtx = activeSockets.get(r);
                    if (rCtx != null && rCtx.session.isOpen()) {
                        rCtx.send(text);
                    }
                } 
                
                else if ("INVITE_ACCEPTED".equals(type)) {
                    String s = json.get("sender").getAsString().trim().toLowerCase();
                    String r = json.get("receiver").getAsString().trim().toLowerCase();
                    String room = json.get("room").getAsString();

                    saveConnectionToDatabase(s, r);
                    establishedConnections.add(room);

                    io.javalin.websocket.WsContext rCtx = activeSockets.get(r);
                    io.javalin.websocket.WsContext sCtx = activeSockets.get(s);
                    
                    if (rCtx != null && rCtx.session.isOpen()) rCtx.send(text);
                    if (sCtx != null && sCtx.session.isOpen()) sCtx.send(text);
                } 
                
                else if ("TEXT".equals(type) || "MEDIA".equals(type)) {
                    String room = json.get("room").getAsString();
                    String s = json.get("sender").getAsString().trim().toLowerCase();
                    String r = json.get("receiver").getAsString().trim().toLowerCase();

                    // Fallback database lookup check
                    boolean isConnected = establishedConnections.contains(room);
                    if (!isConnected) {
                        try (Connection conn = DriverManager.getConnection(DB_URL);
                             PreparedStatement ps = conn.prepareStatement(
                                 "SELECT 1 FROM connections WHERE (user1=? AND user2=?) OR (user1=? AND user2=?)")) {
                            ps.setString(1, s); ps.setString(2, r);
                            ps.setString(3, r); ps.setString(4, s);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    isConnected = true;
                                    establishedConnections.add(room);
                                }
                            }
                        } catch (SQLException ex) {
                            System.err.println("Database sync check failed: " + ex.getMessage());
                        }
                    }

                    if (!isConnected) {
                        com.google.gson.JsonObject alert = new com.google.gson.JsonObject();
                        alert.addProperty("type", "INVITE");
                        alert.addProperty("sender", s);
                        alert.addProperty("room", room);
                        
                        io.javalin.websocket.WsContext rCtx = activeSockets.get(r);
                        if (rCtx != null && rCtx.session.isOpen()) {
                            rCtx.send(alert.toString());
                        }
                        return; 
                    }

                    saveMessageToDatabase(room, text);
                    if (!chatHistories.containsKey(room)) chatHistories.put(room, new ArrayList<>());
                    chatHistories.get(room).add(text);

                    io.javalin.websocket.WsContext rCtx = activeSockets.get(r);
                    io.javalin.websocket.WsContext sCtx = activeSockets.get(s);

                    if (rCtx != null && rCtx.session.isOpen()) {
                        json.addProperty("status", "DELIVERED");
                        rCtx.send(json.toString());
                        
                        if (sCtx != null && sCtx.session.isOpen()) {
                            com.google.gson.JsonObject upd = new com.google.gson.JsonObject();
                            upd.addProperty("type", "STATUS_UPDATE");
                            upd.addProperty("room", room);
                            upd.addProperty("sender", r);
                            upd.addProperty("status", "DELIVERED");
                            sCtx.send(upd.toString());
                        }
                    } else {
                        json.addProperty("status", "SENT");
                        if (sCtx != null && sCtx.session.isOpen()) {
                            sCtx.send(json.toString());
                        }
                        
                        String k = r + "_" + s;
                        unreadCounts.put(k, unreadCounts.getOrDefault(k, 0) + 1);

                        // Trigger Web Push Notification Engine fallback
                        String pushJson = userSubscriptions.get(r);
                        if (pushJson != null) {
                            try {
                                Gson gson = new Gson();
                                Subscription sub = gson.fromJson(pushJson, Subscription.class);
                                
                                com.google.gson.JsonObject pPayload = new com.google.gson.JsonObject();
                                pPayload.addProperty("title", capitalizeFirstLetter(s));
                                pPayload.addProperty("body", "TEXT".equals(type) ? json.get("text").getAsString() : "Sent a media file.");
                                
                                Notification notification = new Notification(sub, pPayload.toString());
                                HttpResponse response = pushService.send(notification);
                                System.out.println("Push status code: " + response.getStatusLine().getStatusCode());
                            } catch (Exception e) {
                                System.err.println("Failed sending push payload packet: " + e.getMessage());
                            }
                        }
                    }
                } 
                
                else if ("HISTORY_REQUEST".equals(type)) {
                    String room = json.get("room").getAsString();
                    ArrayList<String> hist = chatHistories.getOrDefault(room, new ArrayList<>());
                    com.google.gson.JsonObject resp = new com.google.gson.JsonObject();
                    resp.addProperty("type", "HISTORY_RESPONSE");
                    resp.addProperty("room", room);
                    resp.add("messages", new com.google.gson.Gson().toJsonTree(hist));
                    ctx.send(resp.toString());
                } 
                
                else if ("TYPING".equals(type)) {
                    String r = json.get("receiver").getAsString().trim().toLowerCase();
                    io.javalin.websocket.WsContext rCtx = activeSockets.get(r);
                    if (rCtx != null && rCtx.session.isOpen()) {
                        rCtx.send(text);
                    }
                } 
                
                else if ("STATUS_UPDATE".equals(type)) {
                    String r = json.get("receiver").getAsString().trim().toLowerCase();
                    String room = json.get("room").getAsString();
                    if ("READ".equals(json.get("status").getAsString())) {
                        String k = socketOwners.get(ctx) + "_" + r;
                        unreadCounts.put(k, 0);
                    }
                    io.javalin.websocket.WsContext rCtx = activeSockets.get(r);
                    if (rCtx != null && rCtx.session.isOpen()) {
                        rCtx.send(text);
                    }
                }
            });
        });

        app.start(7070);
    }

    // --- Core Database Utilities ---

    private static void initializeDatabase(HashMap<String, String> ud, HashSet<String> ec, HashMap<String, ArrayList<String>> ch) {
        try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS connections (user1 TEXT, user2 TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (room_key TEXT, token TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS profile_pics (username TEXT PRIMARY KEY, base64 TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS subscriptions (username TEXT PRIMARY KEY, sub_json TEXT)");

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
                while (rs.next()) ud.put(rs.getString("username"), rs.getString("password"));
            }
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM connections")) {
                while (rs.next()) {
                    String u1 = rs.getString("user1");
                    String u2 = rs.getString("user2");
                    String[] arr = {u1, u2}; java.util.Arrays.sort(arr);
                    ec.add(arr[0] + "_" + arr[1]);
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM messages")) {
                while (rs.next()) {
                    String r = rs.getString("room_key");
                    String t = rs.getString("token");
                    if (!ch.containsKey(r)) ch.put(r, new ArrayList<>());
                    ch.get(r).add(t);
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM profile_pics")) {
                while (rs.next()) userProfilePics.put(rs.getString("username"), rs.getString("base64"));
            }
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM subscriptions")) {
                while (rs.next()) userSubscriptions.put(rs.getString("username"), rs.getString("sub_json"));
            }
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveUserToDatabase(String u, String p) {
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO users VALUES (?, ?)")) {
            ps.setString(1, u); ps.setString(2, p); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveConnectionToDatabase(String u1, String u2) {
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO connections VALUES (?, ?)")) {
            ps.setString(1, u1); ps.setString(2, u2); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveMessageToDatabase(String r, String t) {
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement ps = conn.prepareStatement("INSERT INTO messages (room_key, token) VALUES (?, ?)")) {
            ps.setString(1, r); ps.setString(2, t); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveProfilePicToDatabase(String u, String p) {
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO profile_pics VALUES (?, ?)")) {
            ps.setString(1, u); ps.setString(2, p); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }
    
    private static void saveSubscriptionToDatabase(String u, String s) {
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO subscriptions VALUES (?, ?)")) {
            ps.setString(1, u); ps.setString(2, s); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static String capitalizeFirstLetter(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
