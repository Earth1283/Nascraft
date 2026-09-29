package me.bounser.nascraft.database.commands;

import me.bounser.nascraft.database.SqlDialect;
import me.bounser.nascraft.database.SqlDialects;
import me.bounser.nascraft.economy.EconomyMath;
import me.bounser.nascraft.economy.MacroSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence for the economy engine. Portable SQL (SQLite + MySQL): natural
 * keys only, no auto-increment, upserts through {@link SqlDialect}.
 */
public final class EconomyData {

    private EconomyData() {}

    public static void createTables(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS economy_windows (" +
                    "ts BIGINT NOT NULL, " +
                    "node VARCHAR(64) NOT NULL, " +
                    "created DOUBLE NOT NULL, " +
                    "destroyed DOUBLE NOT NULL, " +
                    "taxes DOUBLE NOT NULL, " +
                    "trades BIGINT NOT NULL, " +
                    "PRIMARY KEY (ts, node))");
            st.execute("CREATE TABLE IF NOT EXISTS economy_snapshots (" +
                    "ts BIGINT PRIMARY KEY, " +
                    "cpi DOUBLE, inflation DOUBLE, gdp DOUBLE, real_gdp DOUBLE, " +
                    "money_supply DOUBLE, velocity DOUBLE, output_gap DOUBLE, " +
                    "phase VARCHAR(16), recession INT, policy_rate DOUBLE, liquidity DOUBLE, " +
                    "tax_scale DOUBLE, price_level DOUBLE, treasury DOUBLE, ubi DOUBLE, gini DOUBLE, " +
                    "debt DOUBLE, taxes DOUBLE, created DOUBLE, destroyed DOUBLE, trades BIGINT, " +
                    "sectors TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS economy_state (" +
                    "k VARCHAR(64) PRIMARY KEY, " +
                    "v DOUBLE NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS economy_shocks (" +
                    "id VARCHAR(32) PRIMARY KEY, " +
                    "kind VARCHAR(16) NOT NULL, " +
                    "category VARCHAR(64), " +
                    "impact DOUBLE NOT NULL, " +
                    "start_ts BIGINT NOT NULL, " +
                    "end_ts BIGINT NOT NULL)");
        }
        // Index for the trailing-24h aggregation; ignore "already exists" on MySQL.
        try (Statement st = c.createStatement()) {
            st.execute("CREATE INDEX idx_trade_log_day ON trade_log(day)");
        } catch (SQLException ignored) { }
    }

    // ------------------------------------------------------------------
    // Ledger windows
    // ------------------------------------------------------------------

    public record Totals(double created, double destroyed, double taxes, long trades) {
        public double gdp() { return created + destroyed; }
    }

    public static void insertWindow(Connection c, long ts, String node, double created, double destroyed,
                                    double taxes, long trades) throws SQLException {
        SqlDialect d = SqlDialects.current();
        String sql = "INSERT INTO economy_windows (ts, node, created, destroyed, taxes, trades) VALUES (?, ?, ?, ?, ?, ?)" +
                d.onConflictUpdate("ts, node") +
                "created = created + " + d.inserted("created") + ", " +
                "destroyed = destroyed + " + d.inserted("destroyed") + ", " +
                "taxes = taxes + " + d.inserted("taxes") + ", " +
                "trades = trades + " + d.inserted("trades");
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, ts);
            p.setString(2, node);
            p.setDouble(3, created);
            p.setDouble(4, destroyed);
            p.setDouble(5, taxes);
            p.setLong(6, trades);
            p.executeUpdate();
        }
    }

    public static Totals sumWindows(Connection c, long sinceTs) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT COALESCE(SUM(created),0), COALESCE(SUM(destroyed),0), COALESCE(SUM(taxes),0), COALESCE(SUM(trades),0) " +
                "FROM economy_windows WHERE ts > ?")) {
            p.setLong(1, sinceTs);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return new Totals(0, 0, 0, 0);
                return new Totals(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getLong(4));
            }
        }
    }

    // ------------------------------------------------------------------
    // Snapshots
    // ------------------------------------------------------------------

    public static void insertSnapshot(Connection c, MacroSnapshot s) throws SQLException {
        String sql = SqlDialects.current().replaceInto() + " economy_snapshots (ts, cpi, inflation, gdp, real_gdp, money_supply, " +
                "velocity, output_gap, phase, recession, policy_rate, liquidity, tax_scale, price_level, treasury, ubi, gini, " +
                "debt, taxes, created, destroyed, trades, sectors) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            int i = 1;
            p.setLong(i++, s.timestamp());
            p.setDouble(i++, s.cpi());
            p.setDouble(i++, s.inflation());
            p.setDouble(i++, s.gdp());
            p.setDouble(i++, s.realGdp());
            p.setDouble(i++, s.moneySupply());
            p.setDouble(i++, s.velocity());
            p.setDouble(i++, s.outputGap());
            p.setString(i++, s.phase().name());
            p.setInt(i++, s.recession() ? 1 : 0);
            p.setDouble(i++, s.policyRate());
            p.setDouble(i++, s.liquidity());
            p.setDouble(i++, s.taxScale());
            p.setDouble(i++, s.priceLevel());
            p.setDouble(i++, s.treasury());
            p.setDouble(i++, s.ubiPerCapita());
            p.setDouble(i++, s.gini());
            p.setDouble(i++, s.outstandingDebt());
            p.setDouble(i++, s.taxes());
            p.setDouble(i++, s.moneyCreated());
            p.setDouble(i++, s.moneyDestroyed());
            p.setLong(i++, s.trades());
            p.setString(i, encodeSectors(s.sectorIndices()));
            p.executeUpdate();
        }
    }

    /**
     * Snapshots since {@code sinceTs}, oldest first, thinned to at most
     * {@code maxPoints} evenly spaced rows (the latest row is always kept).
     */
    public static List<MacroSnapshot> loadSnapshots(Connection c, long sinceTs, int maxPoints) throws SQLException {
        List<MacroSnapshot> all = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM economy_snapshots WHERE ts >= ? ORDER BY ts ASC")) {
            p.setLong(1, sinceTs);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) all.add(read(rs));
            }
        }
        if (maxPoints <= 0 || all.size() <= maxPoints) return all;
        List<MacroSnapshot> thin = new ArrayList<>(maxPoints);
        double step = (double) (all.size() - 1) / (maxPoints - 1);
        for (int k = 0; k < maxPoints; k++) thin.add(all.get((int) Math.round(k * step)));
        return thin;
    }

    public static MacroSnapshot latestSnapshot(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM economy_snapshots ORDER BY ts DESC LIMIT 1");
             ResultSet rs = p.executeQuery()) {
            return rs.next() ? read(rs) : null;
        }
    }

    /** CPI reading closest to (at or before) {@code ts}; the earliest one if none precede it. */
    public static double[] cpiNear(Connection c, long ts) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT ts, cpi FROM economy_snapshots WHERE ts <= ? ORDER BY ts DESC LIMIT 1")) {
            p.setLong(1, ts);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) return new double[] { rs.getLong(1), rs.getDouble(2) };
            }
        }
        try (PreparedStatement p = c.prepareStatement("SELECT ts, cpi FROM economy_snapshots ORDER BY ts ASC LIMIT 1");
             ResultSet rs = p.executeQuery()) {
            if (rs.next()) return new double[] { rs.getLong(1), rs.getDouble(2) };
        }
        return null;
    }

    private static MacroSnapshot read(ResultSet rs) throws SQLException {
        EconomyMath.Phase phase;
        try { phase = EconomyMath.Phase.valueOf(rs.getString("phase")); }
        catch (RuntimeException e) { phase = EconomyMath.Phase.EXPANSION; }
        return new MacroSnapshot(
                rs.getLong("ts"), rs.getDouble("cpi"), rs.getDouble("inflation"), rs.getDouble("gdp"),
                rs.getDouble("real_gdp"), rs.getDouble("money_supply"), rs.getDouble("velocity"),
                rs.getDouble("output_gap"), phase, rs.getInt("recession") == 1, rs.getDouble("policy_rate"),
                rs.getDouble("liquidity"), rs.getDouble("tax_scale"), rs.getDouble("price_level"),
                rs.getDouble("treasury"), rs.getDouble("ubi"), rs.getDouble("gini"), rs.getDouble("debt"),
                rs.getDouble("taxes"), rs.getDouble("created"), rs.getDouble("destroyed"), rs.getLong("trades"),
                decodeSectors(rs.getString("sectors")));
    }

    static String encodeSectors(Map<String, Double> sectors) {
        StringBuilder sb = new StringBuilder();
        for (var e : sectors.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey().replace(";", "").replace("=", "")).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static Map<String, Double> decodeSectors(String raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try { out.put(part.substring(0, eq), Double.parseDouble(part.substring(eq + 1))); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Key/value state (policy + treasury)
    // ------------------------------------------------------------------

    public static Map<String, Double> loadState(Connection c) throws SQLException {
        Map<String, Double> m = new HashMap<>();
        try (PreparedStatement p = c.prepareStatement("SELECT k, v FROM economy_state");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) m.put(rs.getString(1), rs.getDouble(2));
        }
        return m;
    }

    public static void saveState(Connection c, Map<String, Double> values) throws SQLException {
        SqlDialect d = SqlDialects.current();
        String sql = "INSERT INTO economy_state (k, v) VALUES (?, ?)" + d.onConflictUpdate("k") + "v = " + d.inserted("v");
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (var e : values.entrySet()) {
                if (e.getValue() == null || !Double.isFinite(e.getValue())) continue;
                p.setString(1, e.getKey());
                p.setDouble(2, e.getValue());
                p.addBatch();
            }
            p.executeBatch();
        }
    }

    /** Atomically adds {@code delta} to a state value (creating it at {@code delta}). */
    public static void addToState(Connection c, String key, double delta) throws SQLException {
        SqlDialect d = SqlDialects.current();
        String sql = "INSERT INTO economy_state (k, v) VALUES (?, ?)" + d.onConflictUpdate("k") + "v = v + " + d.inserted("v");
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, key);
            p.setDouble(2, delta);
            p.executeUpdate();
        }
    }

    /**
     * Atomically subtracts {@code amount} if the value stays at or above {@code floor}.
     * Safe across servers sharing one database.
     * @return true if the withdrawal happened
     */
    public static boolean withdrawFromState(Connection c, String key, double amount, double floor) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE economy_state SET v = v - ? WHERE k = ? AND v - ? >= ?")) {
            p.setDouble(1, amount);
            p.setString(2, key);
            p.setDouble(3, amount);
            p.setDouble(4, floor);
            return p.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------
    // Shocks
    // ------------------------------------------------------------------

    public record ShockRow(String id, String kind, String category, double impact, long start, long end) {}

    public static void saveShock(Connection c, ShockRow s) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(SqlDialects.current().replaceInto() +
                " economy_shocks (id, kind, category, impact, start_ts, end_ts) VALUES (?, ?, ?, ?, ?, ?)")) {
            p.setString(1, s.id());
            p.setString(2, s.kind());
            p.setString(3, s.category());
            p.setDouble(4, s.impact());
            p.setLong(5, s.start());
            p.setLong(6, s.end());
            p.executeUpdate();
        }
    }

    public static List<ShockRow> loadShocks(Connection c, long endsAfter) throws SQLException {
        List<ShockRow> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT id, kind, category, impact, start_ts, end_ts FROM economy_shocks WHERE end_ts > ? ORDER BY start_ts DESC")) {
            p.setLong(1, endsAfter);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(new ShockRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getDouble(4), rs.getLong(5), rs.getLong(6)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Wealth + trading analytics
    // ------------------------------------------------------------------

    public record MoneySupply(double total, int holders) {}

    public static MoneySupply moneySupply(Connection c) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(balance),0) FROM balances");
             ResultSet rs = p.executeQuery()) {
            return rs.next() ? new MoneySupply(rs.getDouble(2), rs.getInt(1)) : new MoneySupply(0, 0);
        }
    }

    /** uuid → balance plus latest portfolio worth recorded within {@code sinceDay}. */
    public static Map<String, Double> wealthByPlayer(Connection c, int sinceDay) throws SQLException {
        Map<String, Double> wealth = new HashMap<>();
        try (PreparedStatement p = c.prepareStatement("SELECT uuid, balance FROM balances");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) wealth.put(rs.getString(1), rs.getDouble(2));
        }
        Map<String, double[]> latest = new HashMap<>();
        try (PreparedStatement p = c.prepareStatement("SELECT uuid, day, worth FROM portfolios_worth WHERE day >= ?")) {
            p.setInt(1, sinceDay);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    double[] cur = latest.get(rs.getString(1));
                    int day = rs.getInt(2);
                    if (cur == null || day > cur[0]) latest.put(rs.getString(1), new double[] { day, rs.getDouble(3) });
                }
            }
        }
        for (var e : latest.entrySet()) wealth.merge(e.getKey(), e.getValue()[1], Double::sum);
        return wealth;
    }

    public record TraderRow(String uuid, String name, double volume, long trades) {}

    public static List<TraderRow> topTraders(Connection c, int sinceDay, int limit) throws SQLException {
        List<TraderRow> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT t.uuid, MAX(u.name), SUM(t.value) AS vol, COUNT(*) FROM trade_log t " +
                "LEFT JOIN user_names u ON u.uuid = t.uuid WHERE t.day >= ? " +
                "GROUP BY t.uuid ORDER BY vol DESC LIMIT " + Math.max(1, Math.min(limit, 100)))) {
            p.setInt(1, sinceDay);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(new TraderRow(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getLong(4)));
            }
        }
        return out;
    }

    /** All traders' volumes over the window, for concentration (HHI). */
    public static double[] traderVolumes(Connection c, int sinceDay) throws SQLException {
        List<Double> v = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT SUM(value) FROM trade_log WHERE day >= ? GROUP BY uuid")) {
            p.setInt(1, sinceDay);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) v.add(rs.getDouble(1));
            }
        }
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    public record ItemFlow(String identifier, double bought, double sold, long unitsBought, long unitsSold, long trades) {}

    public static List<ItemFlow> itemFlows(Connection c, int sinceDay) throws SQLException {
        List<ItemFlow> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT identifier, " +
                "SUM(CASE WHEN buy = 1 THEN value ELSE 0 END), SUM(CASE WHEN buy = 1 THEN 0 ELSE value END), " +
                "SUM(CASE WHEN buy = 1 THEN amount ELSE 0 END), SUM(CASE WHEN buy = 1 THEN 0 ELSE amount END), COUNT(*) " +
                "FROM trade_log WHERE day >= ? GROUP BY identifier")) {
            p.setInt(1, sinceDay);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(new ItemFlow(rs.getString(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6)));
            }
        }
        return out;
    }

    /** Trades and volume by hour of day (0–23), from the trade log timestamps. */
    public static double[][] hourlyActivity(Connection c, int sinceDay) throws SQLException {
        double[][] out = new double[24][2];
        try (PreparedStatement p = c.prepareStatement(
                "SELECT substr(date, 12, 2) AS h, COUNT(*), SUM(value) FROM trade_log WHERE day >= ? GROUP BY h")) {
            p.setInt(1, sinceDay);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    try {
                        int h = Integer.parseInt(rs.getString(1));
                        if (h >= 0 && h < 24) { out[h][0] = rs.getDouble(2); out[h][1] = rs.getDouble(3); }
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        return out;
    }

    public static void purge(Connection c, long beforeTs) throws SQLException {
        for (String table : new String[] { "economy_windows", "economy_snapshots" }) {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM " + table + " WHERE ts < ?")) {
                p.setLong(1, beforeTs);
                p.executeUpdate();
            }
        }
        try (PreparedStatement p = c.prepareStatement("DELETE FROM economy_shocks WHERE end_ts < ?")) {
            p.setLong(1, beforeTs);
            p.executeUpdate();
        }
    }
}
