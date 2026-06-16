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

        // --- UPDATED LOGIN ENDPOINT ---
        app.get("/api/login", ctx -> {
            // Flexible matching: supports both 'username' and 'user' parameter keys
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

        // --- UPDATED REGISTER ENDPOINT ---
        app.get("/api/register", ctx -> {
            // Flexible matching: supports both 'username' and 'user' parameter keys
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

        app.get("/api/users", ctx -> {
            String viewer = ctx.queryParam("viewer");
            if (viewer != null) viewer = viewer.trim().toLowerCase();

            long currentTime = System.currentTimeMillis();
            StringBuilder responseData = new StringBuilder();

            for (String username : userDatabase.keySet()) {
                if (username.equals(viewer)) {
                    userLastSeen.put(viewer, currentTime);
                }

                long lastSeen = userLastSeen.getOrDefault(username, 0L);
                boolean isOnline = (currentTime - lastSeen) < 15000;

                String unreadKey = viewer + "#" + username;
                int unreads = unreadCounts.getOrDefault(unreadKey, 0);

                String statusFlag = "NONE";
                if (establishedConnections.contains(viewer + ":" + username)) {
                    statusFlag = "CONNECTED";
                } else if (activeInvites.containsKey(username) && activeInvites.get(username).equals(viewer)) {
                    statusFlag = "PENDING";
                }

                String profilePicUrl = "NONE";
                if (statusFlag.equals("CONNECTED") || username.equals(viewer)) {
                    profilePicUrl = userProfilePics.getOrDefault(username, "NONE");
                }

                responseData.append(username).append("###")
                        .append(isOnline ? "true" : "false").append("###")
                        .append(unreads).append("###")
                        .append(statusFlag).append("###")
                        .append(profilePicUrl).append("$$$");
            }

            String finalResult = responseData.toString();
            if (finalResult.endsWith("$$$")) {
                finalResult = finalResult.substring(0, finalResult.length() - 3);
            }
            ctx.result(finalResult);
        });

        app.post("/api/uploadProfilePic", ctx -> {
            String user = ctx.queryParam("user");
            String imagePayload = ctx.body();

            if (user == null || imagePayload.isEmpty()) {
                ctx.status(400).result("FAIL: Invalid upload parameters");
                return;
            }

            String cleanUser = user.trim().toLowerCase();
            userProfilePics.put(cleanUser, imagePayload);
            saveProfilePicToDatabase(cleanUser, imagePayload);
            ctx.result("UPLOAD_SUCCESSFUL");
        });

        app.get("/api/sendInvite", ctx -> {
            String fromUser = ctx.queryParam("from");
            String toUser = ctx.queryParam("to");

            if (fromUser == null || toUser == null) {
                ctx.result("FAIL");
                return;
            }

            String cleanFrom = fromUser.trim().toLowerCase();
            String cleanTo = toUser.trim().toLowerCase();

            activeInvites.put(cleanTo, cleanFrom);
            ctx.result("SUCCESS");
        });

        app.post("/api/sendMessage", ctx -> {
            String fromUser = ctx.queryParam("from");
            String toUser = ctx.queryParam("to");
            String messageBody = ctx.body();

            if (fromUser == null || toUser == null || messageBody.isEmpty()) {
                ctx.status(400).result("Invalid Message Parameters");
                return;
            }

            String cleanFrom = fromUser.trim().toLowerCase();
            String cleanTo = toUser.trim().toLowerCase();

            if (!establishedConnections.contains(cleanFrom + ":" + cleanTo)) {
                ctx.status(403).result("Access Blocked: Channel not verified");
                return;
            }

            String roomKey = (cleanFrom.compareTo(cleanTo) < 0) ? cleanFrom + "#" + cleanTo : cleanTo + "#" + cleanFrom;
            String contentPayloadToken = cleanFrom + ":" + messageBody;

            chatHistories.putIfAbsent(roomKey, new ArrayList<>());
            chatHistories.get(roomKey).add(contentPayloadToken);
            saveMessageToDatabase(roomKey, contentPayloadToken);

            String unreadTrackingKey = cleanTo + "#" + cleanFrom;
            unreadCounts.put(unreadTrackingKey, unreadCounts.getOrDefault(unreadTrackingKey, 0) + 1);

            ctx.result("MESSAGE_DISPATCHED_SUCCESSFULLY");
        });

        app.get("/api/checkInvites", ctx -> {
            String currentUserName = ctx.queryParam("user");
            if (currentUserName == null) {
                ctx.result("NONE");
                return;
            }
            String cleanUser = currentUserName.trim().toLowerCase();
            if (activeInvites.containsKey(cleanUser)) {
                ctx.result("PENDING:" + activeInvites.get(cleanUser));
            } else {
                ctx.result("NONE");
            }
        });

        app.get("/api/respondInvite", ctx -> {
            String currentUserName = ctx.queryParam("user");
            String action = ctx.queryParam("action");
            if (currentUserName == null || action == null) {
                ctx.result("FAIL");
                return;
            }

            String cleanUser = currentUserName.trim().toLowerCase();
            String sender = activeInvites.remove(cleanUser);

            if (action.equalsIgnoreCase("accept") && sender != null) {
                establishedConnections.add(cleanUser + ":" + sender);
                establishedConnections.add(sender + ":" + cleanUser);
                saveConnectionToDatabase(cleanUser, sender);
            }
            ctx.result("SUCCESS");
        });

        app.get("/api/checkConnection", ctx -> {
            String user1 = ctx.queryParam("user1");
            String user2 = ctx.queryParam("user2");

            if (user1 == null || user2 == null) {
                ctx.result("FALSE");
                return;
            }

            String cleanUser1 = user1.trim().toLowerCase();
            String cleanUser2 = user2.trim().toLowerCase();

            if (establishedConnections.contains(cleanUser1 + ":" + cleanUser2)) {
                ctx.result("TRUE");
            } else {
                ctx.result("FALSE");
            }
        });

        app.get("/api/getMessages", ctx -> {
            String from = ctx.queryParam("from").trim().toLowerCase();
            String to = ctx.queryParam("to").trim().toLowerCase();

            String roomKey = (from.compareTo(to) < 0) ? from + "#" + to : to + "#" + from;
            unreadCounts.put(from + "#" + to, 0);

            ArrayList<String> messages = chatHistories.getOrDefault(roomKey, new ArrayList<>());
            ctx.result(String.join("\n", messages));
        });

        String portEnv = System.getenv("PORT");
        int portNumber = (portEnv != null) ? Integer.parseInt(portEnv) : 7070;

        // CRITICAL FIX: Forces Javalin to bind to 0.0.0.0 (all IPs, any network switch)
        app.start("0.0.0.0", portNumber);
        System.out.println("✅ Server permanently listening on all interfaces at port " + portNumber);
    }

    private static void initializeDatabase(HashMap<String, String> userDatabase, HashSet<String> establishedConnections, HashMap<String, ArrayList<String>> chatHistories) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT);");
            stmt.execute("CREATE TABLE IF NOT EXISTS connections (user1 TEXT, user2 TEXT, PRIMARY KEY (user1, user2));");
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT, room_key TEXT, token TEXT);");
            stmt.execute("CREATE TABLE IF NOT EXISTS profile_pics (username TEXT PRIMARY KEY, payload TEXT);");

            stmt.execute("INSERT OR IGNORE INTO users (username, password) VALUES ('help', '1234');");

            ResultSet rsUsers = stmt.executeQuery("SELECT username, password FROM users;");
            while (rsUsers.next()) userDatabase.put(rsUsers.getString("username"), rsUsers.getString("password"));

            ResultSet rsConn = stmt.executeQuery("SELECT user1, user2 FROM connections;");
            while (rsConn.next()) {
                establishedConnections.add(rsConn.getString("user1") + ":" + rsConn.getString("user2"));
                establishedConnections.add(rsConn.getString("user2") + ":" + rsConn.getString("user1"));
            }

            ResultSet rsMsg = stmt.executeQuery("SELECT room_key, token FROM messages ORDER BY id ASC;");
            while (rsMsg.next()) {
                chatHistories.putIfAbsent(rsMsg.getString("room_key"), new ArrayList<>());
                chatHistories.get(rsMsg.getString("room_key")).add(rsMsg.getString("token"));
            }

            ResultSet rsPics = stmt.executeQuery("SELECT username, payload FROM profile_pics;");
            while (rsPics.next()) userProfilePics.put(rsPics.getString("username"), rsPics.getString("payload"));

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
