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
            
            if (user != null) user = user.trim().toLowerCase();

            if (user != null && userDatabase.containsKey(user) && userDatabase.get(user).equals(pass)) {
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

                if (!user.equals("help")) {
                    establishedConnections.add(user + ":help");
                    establishedConnections.add("help:" + user);
                    saveConnectionToDatabase(user, "help");
                }
                ctx.result("SUCCESS: Account created successfully!");
            }
        });

        // 🌐 DYNAMIC PORT RESOLUTION: Fixes host platform timeout disconnects
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null) ? Integer.parseInt(portEnv) : 7070;
        app.start(port);
    }

    // --- Core Database Utilities ---

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
