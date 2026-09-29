package me.bounser.nascraft.database.commands;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Persisted web logins. Only a hash of each token is ever stored. */
public final class WebSessions {

    private WebSessions() {}

    public record Row(String tokenHash, String uuid, String name, long created, long expires, String device, String ip) {}

    public static void createTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS web_sessions (" +
                    "token_hash VARCHAR(64) PRIMARY KEY, " +
                    "uuid VARCHAR(36) NOT NULL, " +
                    "name VARCHAR(32) NOT NULL, " +
                    "created BIGINT NOT NULL, " +
                    "expires BIGINT NOT NULL, " +
                    "device VARCHAR(64), " +
                    "ip VARCHAR(64))");
        }
    }

    public static void insert(Connection c, Row r) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO web_sessions (token_hash, uuid, name, created, expires, device, ip) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            p.setString(1, r.tokenHash());
            p.setString(2, r.uuid());
            p.setString(3, r.name());
            p.setLong(4, r.created());
            p.setLong(5, r.expires());
            p.setString(6, r.device());
            p.setString(7, r.ip());
            p.executeUpdate();
        }
    }

    public static void delete(Connection c, String tokenHash) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("DELETE FROM web_sessions WHERE token_hash = ?")) {
            p.setString(1, tokenHash);
            p.executeUpdate();
        }
    }

    public static void deleteExpired(Connection c, long now) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("DELETE FROM web_sessions WHERE expires < ?")) {
            p.setLong(1, now);
            p.executeUpdate();
        }
    }

    public static List<Row> loadActive(Connection c, long now) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT token_hash, uuid, name, created, expires, device, ip FROM web_sessions WHERE expires >= ?")) {
            p.setLong(1, now);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7)));
            }
        }
        return out;
    }
}
