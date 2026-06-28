package com.edgeoforder.stats;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class SimpleStatsAPI extends JavaPlugin implements Listener {

    private Connection dbConnection;
    private HttpServer httpServer;
    private final Map<UUID, Long> playerSessionStart = new ConcurrentHashMap<>();
    private long serverStartTime = System.currentTimeMillis();
    private int maxOnline = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initDatabase();
        getServer().getPluginManager().registerEvents(this, this);
        startHttpServer();
        loadMaxOnline();
        getLogger().info("SimpleStatsAPI включён. Статистика собирается.");
    }

    @Override
    public void onDisable() {
        if (httpServer != null) httpServer.stop(0);
        closeDatabase();
        getLogger().info("SimpleStatsAPI выключен.");
    }

    private void initDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            dbConnection = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/stats.db");
            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS player_stats (" +
                        "uuid VARCHAR(36) PRIMARY KEY," +
                        "player VARCHAR(32) NOT NULL," +
                        "kills INT DEFAULT 0," +
                        "playtime_seconds INT DEFAULT 0," +
                        "level INT DEFAULT 0," +
                        "dragon_kills INT DEFAULT 0," +
                        "first_join TIMESTAMP DEFAULT 0," +
                        "last_seen TIMESTAMP DEFAULT 0)");
                stmt.execute("CREATE TABLE IF NOT EXISTS server_stats (" +
                        "key VARCHAR(32) PRIMARY KEY," +
                        "value INT DEFAULT 0)");
                stmt.execute("INSERT OR IGNORE INTO server_stats (key, value) VALUES ('max_online', 0)");
                // Для обратной совместимости
                try { stmt.execute("ALTER TABLE player_stats ADD COLUMN first_join TIMESTAMP DEFAULT 0"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE player_stats ADD COLUMN last_seen TIMESTAMP DEFAULT 0"); } catch (SQLException ignored) {}
            }
        } catch (Exception e) {
            getLogger().severe("Ошибка инициализации БД: " + e.getMessage());
        }
    }

    private void loadMaxOnline() {
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT value FROM server_stats WHERE key='max_online'")) {
            if (rs.next()) maxOnline = rs.getInt("value");
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void saveMaxOnline(int newMax) {
        try (PreparedStatement stmt = dbConnection.prepareStatement(
                "UPDATE server_stats SET value=? WHERE key='max_online'")) {
            stmt.setInt(1, newMax);
            stmt.execute();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void updateMaxOnline() {
        int current = Bukkit.getOnlinePlayers().size();
        if (current > maxOnline) {
            maxOnline = current;
            saveMaxOnline(maxOnline);
        }
    }

    private void closeDatabase() {
        try { if (dbConnection != null && !dbConnection.isClosed()) dbConnection.close(); }
        catch (SQLException e) { e.printStackTrace(); }
    }

    private void startHttpServer() {
        try {
            FileConfiguration config = getConfig();
            int port = config.getInt("http-port", 8080);
            String host = config.getString("http-host", "0.0.0.0");
            httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            // Все обработчики теперь с префиксом /api
            httpServer.createContext("/api/top", new TopHandler());
            httpServer.createContext("/api/status", new StatusHandler());
            httpServer.createContext("/api/player", new PlayerHandler());
            httpServer.setExecutor(null);
            httpServer.start();
            getLogger().info("HTTP API запущен на http://" + host + ":" + port + "/api/top, /api/status, /api/player");
        } catch (IOException e) {
            getLogger().severe("Не удалось запустить HTTP сервер: " + e.getMessage());
        }
    }

    private void setCors(HttpExchange exchange) {
        FileConfiguration config = getConfig();
        List<String> allowedOrigins = config.getStringList("cors-allowed-origins");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && allowedOrigins.contains(origin))
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        else if (allowedOrigins.contains("*"))
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private class TopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            String type = "kills";
            if (query != null && query.startsWith("type=")) type = query.substring(5);
            String json = getTopJson(type);
            byte[] response = json.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String json = getStatusJson();
            byte[] response = json.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
        }
    }

    private class PlayerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCors(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            String nick = null;
            if (query != null && query.startsWith("nick=")) nick = query.substring(5);
            if (nick == null || nick.isEmpty()) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            String json = getPlayerJson(nick);
            byte[] response = json.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
        }
    }

    private String getTopJson(String type) {
        String column;
        boolean isTime = false;
        switch (type.toLowerCase()) {
            case "level": column = "level"; break;
            case "time": column = "playtime_seconds"; isTime = true; break;
            case "dragon": column = "dragon_kills"; break;
            default: column = "kills";
        }
        String sql = "SELECT player, uuid, " + column + " as value FROM player_stats ORDER BY " + column + " DESC LIMIT 100";
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            List<Map<String, Object>> list = new ArrayList<>();
            int rank = 1;
            while (rs.next()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rank", rank++);
                entry.put("player", rs.getString("player"));
                entry.put("uuid", rs.getString("uuid"));
                long val = rs.getLong("value");
                entry.put("value", isTime ? Math.round(val / 360.0) / 10.0 : val);
                list.add(entry);
            }
            StringBuilder sb = new StringBuilder("{\"top\":[");
            for (int i = 0; i < list.size(); i++) {
                Map<String, Object> m = list.get(i);
                sb.append("{\"rank\":").append(m.get("rank"))
                  .append(",\"player\":\"").append(escapeJson(m.get("player").toString())).append("\"")
                  .append(",\"uuid\":\"").append(m.get("uuid").toString()).append("\"")
                  .append(",\"value\":").append(m.get("value")).append("}");
                if (i < list.size() - 1) sb.append(",");
            }
            sb.append("]}");
            return sb.toString();
        } catch (SQLException e) {
            e.printStackTrace();
            return "{\"error\":\"Database error\"}";
        }
    }

    private String getStatusJson() {
        long uptimeSeconds = (System.currentTimeMillis() - serverStartTime) / 1000;
        int online = Bukkit.getOnlinePlayers().size();
        int maxPlayers = Bukkit.getMaxPlayers();
        String version = Bukkit.getVersion();
        return String.format(
            "{\"online\":%d,\"max_online_all_time\":%d,\"max_players\":%d,\"uptime_seconds\":%d,\"server_online\":true,\"version\":\"%s\"}",
            online, maxOnline, maxPlayers, uptimeSeconds, escapeJson(version)
        );
    }

    private String getPlayerJson(String nick) {
        String sql = "SELECT uuid, player, kills, playtime_seconds, level, dragon_kills, first_join, last_seen " +
                     "FROM player_stats WHERE lower(player) = lower(?)";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, nick);
            ResultSet rs = stmt.executeQuery();
            if (!rs.next()) return "{\"error\":\"Player not found\"}";
            String uuid = rs.getString("uuid");
            String playerName = rs.getString("player");
            int kills = rs.getInt("kills");
            int playtime = rs.getInt("playtime_seconds");
            int level = rs.getInt("level");
            int dragonKills = rs.getInt("dragon_kills");
            long firstJoin = rs.getLong("first_join");
            long lastSeen = rs.getLong("last_seen");
            String skinUrl = "https://mc-heads.net/avatar/" + escapeJson(playerName) + "/128";
            return String.format(
                "{\"player\":{" +
                "\"nick\":\"%s\"," +
                "\"uuid\":\"%s\"," +
                "\"skin\":\"%s\"," +
                "\"firstJoin\":%d," +
                "\"lastSeen\":%d," +
                "\"kills\":%d," +
                "\"playtimeSeconds\":%d," +
                "\"level\":%d," +
                "\"dragonKills\":%d" +
                "}}",
                escapeJson(playerName), uuid, skinUrl, firstJoin, lastSeen, kills, playtime, level, dragonKills
            );
        } catch (SQLException e) {
            e.printStackTrace();
            return "{\"error\":\"Database error\"}";
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // События
    @EventHandler
    public void onPlayerKill(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim))
            incrementStat(killer.getUniqueId(), killer.getName(), "kills", 1);
    }

    @EventHandler
    public void onDragonKill(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon) {
            Player killer = event.getEntity().getKiller();
            if (killer != null)
                incrementStat(killer.getUniqueId(), killer.getName(), "dragon_kills", 1);
        }
    }

    @EventHandler
    public void onLevelChange(PlayerLevelChangeEvent event) {
        updatePlayerLevel(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        long now = System.currentTimeMillis();
        playerSessionStart.put(p.getUniqueId(), now);
        upsertPlayer(p.getUniqueId(), p.getName(), now);
        updatePlayerLevel(p);
        updateMaxOnline();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Long start = playerSessionStart.remove(p.getUniqueId());
        if (start != null) {
            long seconds = (System.currentTimeMillis() - start) / 1000;
            if (seconds > 0)
                incrementStat(p.getUniqueId(), p.getName(), "playtime_seconds", (int) seconds);
        }
        updateLastSeen(p.getUniqueId(), p.getName());
        updateMaxOnline();
    }

    private void updatePlayerLevel(Player player) {
        int level = player.getLevel();
        String sql = "INSERT INTO player_stats (uuid, player, level) VALUES (?, ?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET level = ?, player = ?";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, player.getUniqueId().toString());
            stmt.setString(2, player.getName());
            stmt.setInt(3, level);
            stmt.setInt(4, level);
            stmt.setString(5, player.getName());
            stmt.execute();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void updateLastSeen(UUID uuid, String playerName) {
        long now = System.currentTimeMillis();
        String sql = "UPDATE player_stats SET last_seen = ?, player = ? WHERE uuid = ?";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setLong(1, now);
            stmt.setString(2, playerName);
            stmt.setString(3, uuid.toString());
            stmt.execute();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void upsertPlayer(UUID uuid, String playerName, long joinTime) {
        String checkSql = "SELECT first_join FROM player_stats WHERE uuid = ?";
        try (PreparedStatement checkStmt = dbConnection.prepareStatement(checkSql)) {
            checkStmt.setString(1, uuid.toString());
            ResultSet rs = checkStmt.executeQuery();
            if (rs.next()) {
                String updateSql = "UPDATE player_stats SET last_seen = ?, player = ? WHERE uuid = ?";
                try (PreparedStatement updStmt = dbConnection.prepareStatement(updateSql)) {
                    updStmt.setLong(1, joinTime);
                    updStmt.setString(2, playerName);
                    updStmt.setString(3, uuid.toString());
                    updStmt.execute();
                }
            } else {
                String insertSql = "INSERT INTO player_stats (uuid, player, kills, playtime_seconds, level, dragon_kills, first_join, last_seen) " +
                                   "VALUES (?, ?, 0, 0, 0, 0, ?, ?)";
                try (PreparedStatement insStmt = dbConnection.prepareStatement(insertSql)) {
                    insStmt.setString(1, uuid.toString());
                    insStmt.setString(2, playerName);
                    insStmt.setLong(3, joinTime);
                    insStmt.setLong(4, joinTime);
                    insStmt.execute();
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void incrementStat(UUID uuid, String playerName, String column, int delta) {
        String sql = "INSERT INTO player_stats (uuid, player, " + column + ") VALUES (?, ?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET " + column + " = " + column + " + ?, player = ?";
        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, playerName);
            stmt.setInt(3, delta);
            stmt.setInt(4, delta);
            stmt.setString(5, playerName);
            stmt.execute();
        } catch (SQLException e) { e.printStackTrace(); }
    }
}