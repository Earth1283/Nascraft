package me.bounser.nascraft.exchange.regulatory;

import me.bounser.nascraft.Nascraft;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Immutable regulatory log.  The DB user must have INSERT-only on this table
 * (enforced at DB level per the design doc).
 *
 * Also tracks frozen and banned players in memory.
 */
public class RegulatoryLog {

    private static RegulatoryLog instance;

    private final Connection connection;

    // UUID → EnforcementLevel (FREEZE or BAN currently active)
    private final Map<UUID, EnforcementLevel> activeRestrictions = new ConcurrentHashMap<>();

    private RegulatoryLog(Connection connection) {
        this.connection = connection;
        ensureTable();
        loadActiveRestrictions();
    }

    public static void init(Connection connection) {
        instance = new RegulatoryLog(connection);
    }

    public static RegulatoryLog getInstance() {
        if (instance == null) throw new IllegalStateException("RegulatoryLog not initialised");
        return instance;
    }

    // ── Schema ─────────────────────────────────────────────────────────────

    private void ensureTable() {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS regulatory_log (
                    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                    target_uuid  VARCHAR(36)  NOT NULL,
                    level        VARCHAR(16)  NOT NULL,
                    reason       TEXT         NOT NULL,
                    ts           BIGINT       NOT NULL,
                    issued_by    VARCHAR(36)
                ) ENGINE=InnoDB;
                """);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger()
                    .log(Level.SEVERE, "Failed to create regulatory_log table", e);
        }
    }

    private void loadActiveRestrictions() {
        // Replay log to find latest action per player
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT target_uuid, level FROM regulatory_log ORDER BY id ASC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("target_uuid"));
                EnforcementLevel lvl = EnforcementLevel.valueOf(rs.getString("level"));
                if (lvl == EnforcementLevel.FREEZE || lvl == EnforcementLevel.BAN) {
                    activeRestrictions.put(uuid, lvl);
                } else {
                    // WARNING / FINE don't block trading; if this is a WARNING after a BAN
                    // that's unusual and we won't clear the BAN automatically
                }
            }
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger()
                    .warning("Could not load active restrictions: " + e.getMessage());
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Record a regulatory action. Returns the persisted action.
     */
    public RegulatoryAction record(UUID targetUuid, EnforcementLevel level,
                                   String reason, UUID issuedBy) {
        long ts = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO regulatory_log (target_uuid, level, reason, ts, issued_by) VALUES (?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, targetUuid.toString());
            ps.setString(2, level.name());
            ps.setString(3, reason);
            ps.setLong(4, ts);
            ps.setString(5, issuedBy != null ? issuedBy.toString() : null);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            long id = keys.next() ? keys.getLong(1) : -1;

            RegulatoryAction action = new RegulatoryAction(id, targetUuid, level, reason, ts, issuedBy);

            if (level == EnforcementLevel.FREEZE || level == EnforcementLevel.BAN) {
                activeRestrictions.put(targetUuid, level);
            }

            Nascraft.getInstance().getLogger()
                    .warning("[Regulatory] " + level + " issued to " + targetUuid + ": " + reason);
            return action;
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger()
                    .log(Level.SEVERE, "Failed to write regulatory action", e);
            throw new RuntimeException(e);
        }
    }

    /** Warn a player (no trading restriction). */
    public void warn(UUID uuid, String reason) {
        record(uuid, EnforcementLevel.WARNING, reason, null);
    }

    /** Freeze a player's trading account. */
    public void freeze(UUID uuid, String reason) {
        record(uuid, EnforcementLevel.FREEZE, reason, null);
    }

    /** Fine a player (warn + note — actual currency deduction handled by caller). */
    public void fine(UUID uuid, String reason) {
        record(uuid, EnforcementLevel.FINE, reason, null);
    }

    /** Ban a player from all exchange activity. */
    public void ban(UUID uuid, String reason) {
        record(uuid, EnforcementLevel.BAN, reason, null);
    }

    /** Lift a FREEZE restriction. BAN can only be lifted by admin. */
    public void lift(UUID uuid) {
        activeRestrictions.remove(uuid);
        Nascraft.getInstance().getLogger()
                .info("[Regulatory] Restriction lifted for " + uuid);
    }

    public boolean isFrozen(UUID uuid) {
        EnforcementLevel lvl = activeRestrictions.get(uuid);
        return lvl == EnforcementLevel.FREEZE || lvl == EnforcementLevel.BAN;
    }

    public boolean isBanned(UUID uuid) {
        return activeRestrictions.get(uuid) == EnforcementLevel.BAN;
    }

    public List<RegulatoryAction> getHistory(UUID uuid) {
        List<RegulatoryAction> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM regulatory_log WHERE target_uuid=? ORDER BY id DESC")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String issuedByStr = rs.getString("issued_by");
                out.add(new RegulatoryAction(
                        rs.getLong("id"),
                        UUID.fromString(rs.getString("target_uuid")),
                        EnforcementLevel.valueOf(rs.getString("level")),
                        rs.getString("reason"),
                        rs.getLong("ts"),
                        issuedByStr != null ? UUID.fromString(issuedByStr) : null
                ));
            }
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger()
                    .warning("getHistory error: " + e.getMessage());
        }
        return out;
    }
}
