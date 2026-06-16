package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

public class Main {

    public static ConcurrentHashMap<String, String> userProfilePics = new ConcurrentHashMap<>();
    private static final String DB_URL = "jdbc:sqlite:/app/data/chatlounge.db";

    public static void main(String[] args) {
        HashMap<String, String> userDatabase = new HashMap<>();
        HashMap<String, Long> userLastSeen = new HashMap<>();
        HashMap<String, String> activeInvites = new HashMap<>();
        HashSet<String> establishedConnections = new HashSet<>();
        HashMap<String, Integer> unreadCounts = new HashMap<>();
        HashMap<String, ArrayList<String>> chatHistories = new HashMap<>();

        initializeDatabase(userDatabase, establishedConnections, chatHistories);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
        });

        app.get("/", ctx -> ctx.redirect("/index.html"));

        // --- API LOGIN ENDPOINT (Supports both parameter styles) ---
        app.get("/api/login", ctx -> {
            String user = ctx.queryParam("username") != null ? ctx.queryParam("username") : ctx.queryParam("user");
            String pass = ctx.queryParam("password") != null ? ctx.queryParam("password") : ctx.queryParam("pass");
            
            if (user == null || pass == null || user.trim().isEmpty()) {
                ctx.result("FAIL: Missing login parameters.");
                return;
            }

            user = user.trim().toLowerCase();

            if (userDatabase.containsKey(user) && userDatabase.get(user).equals(pass)) {
                userLastSeen.put(user, System.currentTimeMillis());
                ctx.result("SUCCESS: Access Granted!");
            } else {
                ctx.result("FAIL: Invalid username or password.");
            }
        });

        // --- API REGISTER ENDPOINT (Supports both parameter styles) ---
        app.get("/api/register", ctx -> {
            String user = ctx.queryParam("username") != null ? ctx.queryParam("username") : ctx.queryParam("user");
            String pass = ctx.queryParam("password") != null ? ctx.queryParam("password") : ctx.queryParam("pass");

            if (user == null || pass == null || user.trim().isEmpty() || pass.trim().isEmpty()) {
                ctx.result("FAIL: Username and password cannot be empty!");
                return;
            }

            user = user.trim().toLowerCase();

            if (userDatabase.containsKey(user)) {
                ctx.result("FAIL: Username already exists!");
            } else {
                userDatabase.put(user, pass);
                saveUserToDatabase(user, pass);

                if (!user.equals("help")) {
                    establishedConnections.add(user + ":help");
                    establishedConnections.add("help:" + user);
                    saveConnectionToDatabase(user, "help");
                }
                ctx.result("SUCCESS: Account created successfully!");
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

        // --- WEB SOCKET ROUTING PIPELINE ---
        app.ws("/ws", ws -> {
            ConcurrentHashMap<String, io.javalin.websocket.WsContext> activeSockets = new ConcurrentHashMap<>();
            ConcurrentHashMap<io.javalin.websocket.WsContext, String> socketOwners = new ConcurrentHashMap<>();

            ws.onConnect(ctx -> {
                // Connection initiated cleanly
            });

            ws.onClose(ctx -> {
                String u = socketOwners.remove((io.javalin.websocket.WsContext) ctx);
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
                    if (rCtx != null && rCtx.session().isOpen()) {
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
                    
                    if (rCtx != null && rCtx.session().isOpen()) rCtx.send(text);
                    if (sCtx != null && sCtx.session().isOpen()) sCtx.send(text);
                } 
                
                else if ("TEXT".equals(type) || "MEDIA".equals(type)) {
                    String room = json.get("room").getAsString();
                    String s = json.get("sender").getAsString().trim().toLowerCase();
                    String r = json.get("receiver").getAsString().trim().toLowerCase();

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
                        if (rCtx != null && rCtx.session().isOpen()) {
                            rCtx.send(alert.toString());
                        }
                        return; 
                    }

                    saveMessageToDatabase(room, text);
                    if (!chatHistories.containsKey(room)) chatHistories.put(room, new ArrayList<>());
                    chatHistories.get(room).add(text);

                    io.javalin.websocket.WsContext rCtx = activeSockets.get(r);
                    io.javalin.websocket.WsContext sCtx = activeSockets.get(s);

                    if (rCtx != null && rCtx.session().isOpen()) {
                        json.addProperty("status", "DELIVERED");
                        rCtx.send(json.toString());
                        
                        if (sCtx != null && sCtx.session().isOpen()) {
                            com.google.gson.JsonObject upd = new com.google.gson.JsonObject();
                            upd.addProperty("type", "STATUS_UPDATE");
                            upd.addProperty("room", room);
                            upd.addProperty("sender", r);
                            upd.addProperty("status", "DELIVERED");
                            sCtx.send(upd.toString());
                        }
                    } else {
                        json.addProperty("status", "SENT");
                        if (sCtx != null && sCtx.session().isOpen()) {
                            sCtx.send(json.toString());
                        }
                        
                        String k = r + "_" + s;
                        unreadCounts.put(k, unreadCounts.getOrDefault(k, 0) + 1);
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
                    if (rCtx != null && rCtx.session().isOpen()) {
                        rCtx.send(text);
                    }
                } 
                
                else if ("STATUS_UPDATE".equals(type)) {
                    String r = json.get("receiver").getAsString().trim().toLowerCase();
                    String room = json.get("room").getAsString();
                    if ("READ".equals(json.get("status").getAsString())) {
                        String k = socketOwners.get((io.javalin.websocket.WsContext) ctx) + "_" + r;
                        unreadCounts.put(k, 0);
                    }
                    io.javalin.websocket.WsContext rCtx = activeSockets.get(r);
                    if (rCtx != null && rCtx.session().isOpen()) {
                        rCtx.send(text);
                    }
                }
            });
        });

        // 🌐 DYNAMIC PORT RESOLUTION: Fixes host platform timeout disconnects
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null) ? Integer.parseInt(portEnv) : 7070;
        app.start(port);
    }

    // --- Core Database Utilities ---

    private static void initializeDatabase(HashMap<String, String> ud, HashSet<String> ec, HashMap<String, ArrayList<String>> ch) {
        try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS connections (user1 TEXT, user2 TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (room_key TEXT, token TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS profile_pics (username TEXT PRIMARY KEY, base64 TEXT)");

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
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveUserToDatabase(String u, String p) {
        try (Connection conn = DriverManager.getConnection(DB_URL); 
             PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO users VALUES (?, ?)")) {
            ps.setString(1, u); ps.setString(2, p); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveConnectionToDatabase(String u1, String u2) {
        try (Connection conn = DriverManager.getConnection(DB_URL); 
             PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO connections VALUES (?, ?)")) {
            ps.setString(1, u1); ps.setString(2, u2); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveMessageToDatabase(String r, String t) {
        try (Connection conn = DriverManager.getConnection(DB_URL); 
             PreparedStatement ps = conn.prepareStatement("INSERT INTO messages (room_key, token) VALUES (?, ?)")) {
            ps.setString(1, r); ps.setString(2, t); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }

    private static void saveProfilePicToDatabase(String u, String p) {
        try (Connection conn = DriverManager.getConnection(DB_URL); 
             PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO profile_pics VALUES (?, ?)")) {
            ps.setString(1, u); ps.setString(2, p); ps.executeUpdate();
        } catch (SQLException e) { System.err.println(e.getMessage()); }
    }
}
