package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

public class Main {

    // 👤 Global Core Storage: Maps username -> Base64 Image Payload String (Backed by SQL)
    public static ConcurrentHashMap<String, String> userProfilePics = new ConcurrentHashMap<>();

    // Dynamic Database URL determination for Railway Volume compatibility vs Local execution
    private static final String DB_URL = getDatabaseUrl();

    private static String getDatabaseUrl() {
        java.io.File railwayVolumeDir = new java.io.File("/app/data");
        if (railwayVolumeDir.exists() && railwayVolumeDir.isDirectory()) {
            return "jdbc:sqlite:/app/data/chatlounge.db";
        }
        return "jdbc:sqlite:chatlounge.db";
    }

    public static void main(String[] args) {
        // 1. Registered User Base
        HashMap<String, String> userDatabase = new HashMap<>();

        // 2. Heartbeat Monitor: Track when users last pinged the server (Kept in RAM, resets naturally)
        HashMap<String, Long> userLastSeen = new HashMap<>();

        // 3. Pending Incoming Invitations (Receiver -> Sender)
        HashMap<String, String> activeInvites = new HashMap<>();

        // 4. Multi-Chat Map: Tracks who has open channels with whom ("user1:user2")
        HashSet<String> establishedConnections = new HashSet<>();

        // 5. Unread Message Counter Map (Recipient#Sender -> Count)
        HashMap<String, Integer> unreadCounts = new HashMap<>();

        // 6. Global Chat Repository (RoomKey -> List of encoded message tokens "sender:text")
        HashMap<String, ArrayList<String>> chatHistories = new HashMap<>();

        // 💾 LOAD ALL DATA FROM DATABASE FILE ON STARTUP
        initializeDatabase(userDatabase, establishedConnections, chatHistories);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/", Location.CLASSPATH);
        });

        // --- 🚪 API CHANNELS & SECURITY GATES ---

        app.get("/api/login", ctx -> {
            String user = ctx.queryParam("username") != null ? ctx.queryParam("username") : ctx.queryParam("user");
            String pass = ctx.queryParam("password") != null ? ctx.queryParam("password") : ctx.queryParam("pass");
            if (user != null) user = user.trim().toLowerCase();

            if (userDatabase.containsKey(user) && userDatabase.get(user).equals(pass)) {
                userLastSeen.put(user, System.currentTimeMillis());
                ctx.result("SUCCESS: Access Granted!");
            } else {
                ctx.result("FAIL: Invalid username or password.");
            }
        });

        app.get("/api/register", ctx -> {
            String user = ctx.queryParam("username") != null ? ctx.queryParam("username") : ctx.queryParam("user");
            String pass = ctx.queryParam("password") != null ? ctx.queryParam("password") : ctx.queryParam("pass");
            if (user != null) user = user.trim().toLowerCase();

            if (userDatabase.containsKey(user)) {
                ctx.result("FAIL: Username already exists!");
            } else {
                userDatabase.put(user, pass);
                saveUserToDatabase(user, pass);

                // 🤝 AUTO-CONNECT TO HELP CHANNEL IMMEDIATELY
                if (user != null && !user.equals("help")) {
                    establishedConnections.add(user + ":help");
                    establishedConnections.add("help:" + user);
                    saveConnectionToDatabase(user, "help");
                    System.out.println("--> [HELP CHANNEL AUTO-OPENED] " + user + " <--> help");
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
                boolean isOnline = (currentTime - lastSeen) < 7000;

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

            // 💾 PERSIST PROFILE IMAGE TO DISK
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

            // 💾 PERSIST MESSAGE LOG TO DISK
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

                // 💾 PERSIST NEW ACCEPTED CHAT LINK TO DISK
                saveConnectionToDatabase(cleanUser, sender);

                System.out.println("--> [CHANNEL OPENED] " + cleanUser + " <--> " + sender);
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

        // 🌐 Railway Dynamic Port Binding Engine
        String envPort = System.getenv("PORT");
        int port = (envPort != null && !envPort.isEmpty()) ? Integer.parseInt(envPort) : 7070;
        app.start(port);
    }

    // 🗄️ EXTENDED DATABASE CORE PIPELINE FUNCTIONS
    private static void initializeDatabase(HashMap<String, String> userDatabase, HashSet<String> establishedConnections, HashMap<String, ArrayList<String>> chatHistories) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            // 1. Create Tables
            stmt.execute("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT);");
            stmt.execute("CREATE TABLE IF NOT EXISTS connections (user1 TEXT, user2 TEXT, PRIMARY KEY (user1, user2));");
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT, room_key TEXT, token TEXT);");
            stmt.execute("CREATE TABLE IF NOT EXISTS profile_pics (username TEXT PRIMARY KEY, payload TEXT);");

            // Seed base support account
            stmt.execute("INSERT OR IGNORE INTO users (username, password) VALUES ('help', '1234');");

            // 2. Hydrate Accounts Map
            ResultSet rsUsers = stmt.executeQuery("SELECT username, password FROM users;");
            while (rsUsers.next()) {
                userDatabase.put(rsUsers.getString("username"), rsUsers.getString("password"));
            }

            // 3. Hydrate Verified Channel Links
            ResultSet rsConn = stmt.executeQuery("SELECT user1, user2 FROM connections;");
            while (rsConn.next()) {
                String u1 = rsConn.getString("user1");
                String u2 = rsConn.getString("user2");
                establishedConnections.add(u1 + ":" + u2);
                establishedConnections.add(u2 + ":" + u1);
            }

            // 4. Hydrate Chat Log History Repositories
            ResultSet rsMsg = stmt.executeQuery("SELECT room_key, token FROM messages ORDER BY id ASC;");
            while (rsMsg.next()) {
                String key = rsMsg.getString("room_key");
                String token = rsMsg.getString("token");
                chatHistories.putIfAbsent(key, new ArrayList<>());
                chatHistories.get(key).add(token);
            }

            // 5. Hydrate Saved Custom Profile Icons
            ResultSet rsPics = stmt.executeQuery("SELECT username, payload FROM profile_pics;");
            while (rsPics.next()) {
                userProfilePics.put(rsPics.getString("username"), rsPics.getString("payload"));
            }

            System.out.println("--> [DATABASE RESTORED] Loaded Users: " + userDatabase.size() + " | Channels: " + establishedConnections.size() / 2 + " | Pictures: " + userProfilePics.size());

        } catch (SQLException e) {
            System.err.println("Database initialization error: " + e.getMessage());
        }
    }

    private static void saveUserToDatabase(String username, String password) {
        String query = "INSERT OR REPLACE INTO users (username, password) VALUES (?, ?);";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, username);
            pstmt.setString(2, password);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to write account profile row: " + e.getMessage());
        }
    }

    private static void saveConnectionToDatabase(String user1, String user2) {
        String query = "INSERT OR IGNORE INTO connections (user1, user2) VALUES (?, ?);";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, user1);
            pstmt.setString(2, user2);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to secure connection map coordinate: " + e.getMessage());
        }
    }

    private static void saveMessageToDatabase(String roomKey, String token) {
        String query = "INSERT INTO messages (room_key, token) VALUES (?, ?);";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, roomKey);
            pstmt.setString(2, token);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to archive chat message token record: " + e.getMessage());
        }
    }

    private static void saveProfilePicToDatabase(String username, String payload) {
        String query = "INSERT OR REPLACE INTO profile_pics (username, payload) VALUES (?, ?);";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, username);
            pstmt.setString(2, payload);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to backup avatar binary profile token: " + e.getMessage());
        }
    }
}
