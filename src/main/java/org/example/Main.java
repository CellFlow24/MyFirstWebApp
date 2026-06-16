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

    // Global memory states to support frontend endpoints
    public static ConcurrentHashMap<String, String> userDatabase = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, Long> userLastSeen = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, String> activeInvites = new ConcurrentHashMap<>();
    public static HashSet<String> establishedConnections = new HashSet<>();
    public static ConcurrentHashMap<String, ArrayList<String>> chatHistories = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, String> pushSubscriptions = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        HashMap<String, String> localUsers = new HashMap<>();
        HashSet<String> localConns = new HashSet<>();
        HashMap<String, ArrayList<String>> localChats = new HashMap<>();

        initializeDatabase(localUsers, localConns, localChats);

        // Populate concurrent memory maps
        localUsers.forEach(userDatabase::put);
        establishedConnections.addAll(localConns);
        localChats.forEach(chatHistories::put);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.plugins.enableCors(cors -> {
                cors.add(it -> {
                    it.anyHost();
                });
            });
        });

        app.get("/", ctx -> ctx.redirect("/index.html"));

        // --- API LOGIN ENDPOINT ---
        app.get("/api/login", ctx -> {
            String user = ctx.queryParam("username") != null ? ctx.queryParam("username") : ctx.queryParam("user");
            String pass = ctx.queryParam("password") != null ? ctx.queryParam("password") : ctx.queryParam("pass");
            if (user != null) user = user.trim().toLowerCase();

            if (user != null && userDatabase.containsKey(user) && userDatabase.get(user).equals(pass)) {
                userLastSeen.put(user, System.currentTimeMillis());
                ctx.result("SUCCESS: Access Granted!");
            } else {
                ctx.result("FAIL: Invalid username or password.");
            }
        });

        // --- API REGISTER ENDPOINT ---
        app.get("/api/register", ctx -> {
            String user = ctx.queryParam("username") != null ? ctx.queryParam("username") : ctx.queryParam("user");
            String pass = ctx.queryParam("password") != null ? ctx.queryParam("password") : ctx.queryParam("pass");
            if (user != null) user = user.trim().toLowerCase();

            if (user == null || pass == null || user.trim().isEmpty() || pass.trim().isEmpty()) {
                ctx.result("FAIL: Username and password cannot be empty!");
                return;
            }

            if (userDatabase.containsKey(user)) {
                ctx.result("FAIL: Username already exists!");
            } else {
                userDatabase.put(user, pass);
                saveUserToDatabase(user, pass);

                // Default auto-connect to help service desk
                if (!user.equals("help")) {
                    String[] arr = {user, "help"}; java.util.Arrays.sort(arr);
                    establishedConnections.add(arr[0] + "_" + arr[1]);
                    saveConnectionToDatabase(user, "help");
                }
                ctx.result("SUCCESS: Account created successfully!");
            }
        });

        // --- API GET USERS DIRECTORY ---
        app.get("/api/users", ctx -> {
            String viewer = ctx.queryParam("viewer");
            if (viewer != null) {
                viewer = viewer.trim().toLowerCase();
                userLastSeen.put(viewer, System.currentTimeMillis());
            }
            
            StringBuilder sb = new StringBuilder();
            long now = System.currentTimeMillis();

            for (String username : userDatabase.keySet()) {
                long lastSeen = userLastSeen.getOrDefault(username, 0L);
                boolean isOnline = (now - lastSeen) < 10000; // 10 second window
                
                String status = "NONE";
                if (viewer != null && !viewer.equals(username)) {
                    String[] arr = {viewer, username}; java.util.Arrays.sort(arr);
                    if (establishedConnections.contains(arr[0] + "_" + arr[1])) {
                        status = "CONNECTED";
                    } else if (activeInvites.getOrDefault(viewer, "").equals(username) || 
                               activeInvites.getOrDefault(username, "").equals(viewer)) {
                        status = "PENDING";
                    }
                } else if (viewer != null && viewer.equals(username)) {
                    status = "SELF";
                }

                String avatar = userProfilePics.getOrDefault(username, "NONE");
                sb.append(username).append("###")
                  .append(isOnline).append("###")
                  .append("0").append("###") // unread placeholder
                  .append(status).append("###")
                  .append(avatar).append("$$$");
            }
            ctx.result(sb.toString());
        });

        // --- API DISPATCH CHAT INVITATION ---
        app.get("/api/sendInvite", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            if (from != null && to != null) {
                activeInvites.put(to.trim().toLowerCase(), from.trim().toLowerCase());
                ctx.result("SUCCESS");
            } else {
                ctx.result("FAIL");
            }
        });

        // --- API CHECK FOR ACTIVE INVITES ---
        app.get("/api/checkInvites", ctx -> {
            String user = ctx.queryParam("user");
            if (user != null) {
                String sender = activeInvites.get(user.trim().toLowerCase());
                if (sender != null) {
                    ctx.result("PENDING:" + sender);
                    return;
                }
            }
            ctx.result("NONE");
        });

        // --- API RESPOND TO CHAT INVITATION ---
        app.get("/api/respondInvite", ctx -> {
            String user = ctx.queryParam("user");
            String action = ctx.queryParam("action");
            if (user != null && action != null) {
                user = user.trim().toLowerCase();
                String sender = activeInvites.remove(user);
                if (sender != null && action.equalsIgnoreCase("accept")) {
                    String[] arr = {user, sender}; java.util.Arrays.sort(arr);
                    establishedConnections.add(arr[0] + "_" + arr[1]);
                    saveConnectionToDatabase(user, sender);
                }
                ctx.result("SUCCESS");
            } else {
                ctx.result("FAIL");
            }
        });

        // --- API VERIFY CONNECTION GATEWAY ---
        app.get("/api/checkConnection", ctx -> {
            String u1 = ctx.queryParam("user1");
            String u2 = ctx.queryParam("user2");
            if (u1 != null && u2 != null) {
                String[] arr = {u1.trim().toLowerCase(), u2.trim().toLowerCase()}; 
                java.util.Arrays.sort(arr);
                if (u1.equalsIgnoreCase(u2) || establishedConnections.contains(arr[0] + "_" + arr[1])) {
                    ctx.result("TRUE");
                    return;
                }
            }
            ctx.result("FALSE");
        });

        // --- API READ CHAT MESSAGES ---
        app.get("/api/getMessages", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            if (from != null && to != null) {
                String[] arr = {from.trim().toLowerCase(), to.trim().toLowerCase()}; 
                java.util.Arrays.sort(arr);
                String roomKey = arr[0] + "_" + arr[1];
                ArrayList<String> history = chatHistories.getOrDefault(roomKey, new ArrayList<>());
                ctx.result(String.join("\n", history));
            } else {
                ctx.result("");
            }
        });

        // --- API TRANSMIT CHAT MESSAGE ---
        app.post("/api/sendMessage", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            String body = ctx.body();
            if (from != null && to != null && !body.trim().isEmpty()) {
                from = from.trim().toLowerCase();
                to = to.trim().toLowerCase();
                String[] arr = {from, to}; java.util.Arrays.sort(arr);
                String roomKey = arr[0] + "_" + arr[1];
                
                String structuredToken = from + ":" + body;
                chatHistories.computeIfAbsent(roomKey, k -> new ArrayList<>()).add(structuredToken);
                saveMessageToDatabase(roomKey, structuredToken);
                ctx.result("SUCCESS");
            } else {
                ctx.result("FAIL");
            }
        });

        // --- API UPLOAD AVATAR PIC ---
        app.post("/api/uploadProfilePic", ctx -> {
            String user = ctx.queryParam("user");
            String body = ctx.body();
            if (user != null && !body.trim().isEmpty()) {
                user = user.trim().toLowerCase();
                userProfilePics.put(user, body);
                saveProfilePicToDatabase(user, body);
                ctx.result("UPLOAD_SUCCESSFUL");
            } else {
                ctx.result("UPLOAD_FAILED");
            }
        });

        // --- API SAVE PUSH NOTIFICATION SUBSCRIPTION ---
        app.post("/api/saveSubscription", ctx -> {
            String username = ctx.queryParam("username");
            if (username != null) {
                pushSubscriptions.put(username.trim().toLowerCase(), ctx.body());
                ctx.result("SAVED");
            } else {
                ctx.result("FAIL");
            }
        });

        String portEnv = System.getenv("PORT");
        int port = (portEnv != null) ? Integer.parseInt(portEnv) : 7070;
        app.start(port);
    }

    private static void initializeDatabase(HashMap<String, String> userDatabase, HashSet<String> establishedConnections, HashMap<String, ArrayList<String>> chatHistories) {
        try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS connections (user1 TEXT, user2 TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (room_key TEXT, token TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS profile_pics (username TEXT PRIMARY KEY, base64 TEXT)");

            try (ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
                while (rs.next()) userDatabase.put(rs.getString("username"), rs.getString("password"));
            }
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM connections")) {
                while (rs.next()) {
                    String u1 = rs.getString("user1");
                    String u2 = rs.getString("user2");
                    String[] arr = {u1, u2}; java.util.Arrays.sort(arr);
                    establishedConnections.add(arr[0] + "_" + arr[1]);
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM messages")) {
                while (rs.next()) {
                    String r = rs.getString("room_key");
                    String t = rs.getString("token");
                    if (!chatHistories.containsKey(r)) chatHistories.put(r, new ArrayList<>());
                    chatHistories.get(r).add(t);
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
