package me.bounser.nascraft.database.commands;

import me.bounser.nascraft.economy.EconomyMath;
import me.bounser.nascraft.economy.MacroSnapshot;
import me.bounser.nascraft.support.DatabaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EconomyDataTest extends DatabaseTest {
    @BeforeEach
    void createEconomyTables() throws SQLException {
        EconomyData.createTables(connection);
        EconomyData.createTables(connection);
    }

    private static MacroSnapshot snap(long ts, double cpi) {
        Map<String, Double> sectors = new LinkedHashMap<>();
        sectors.put("ores", 104.5);
        sectors.put("food", 97.25);
        return new MacroSnapshot(ts, cpi, 0.001, 5000, 4800, 100000, 0.05, -0.1,
                EconomyMath.Phase.RECOVERY, false, 0.006, 0.99, 0.9, 1.05, 25000, 12, 0.41, 3000,
                150, 2600, 2400, 42, sectors);
    }

    @Test
    @DisplayName("Ledger windows accumulate per (ts, node) and sum over a time range")
    void windows() throws SQLException {
        EconomyData.insertWindow(connection, 1000, "a", 10, 5, 1, 2);
        EconomyData.insertWindow(connection, 1000, "a", 10, 5, 1, 2);
        EconomyData.insertWindow(connection, 2000, "b", 1, 1, 0.5, 1);

        EconomyData.Totals all = EconomyData.sumWindows(connection, 0);
        assertEquals(21, all.created(), 1e-9);
        assertEquals(11, all.destroyed(), 1e-9);
        assertEquals(2.5, all.taxes(), 1e-9);
        assertEquals(5, all.trades());
        assertEquals(32, all.gdp(), 1e-9);

        assertEquals(1, EconomyData.sumWindows(connection, 1500).trades());
        assertEquals(0, EconomyData.sumWindows(connection, 5000).trades());
    }

    @Test
    @DisplayName("State upserts, atomic increments and floor-guarded withdrawals")
    void state() throws SQLException {
        EconomyData.saveState(connection, Map.of("rate", 0.005, "nan", Double.NaN));
        EconomyData.saveState(connection, Map.of("rate", 0.006));
        EconomyData.addToState(connection, "treasury", 100);
        EconomyData.addToState(connection, "treasury", 50);

        Map<String, Double> m = EconomyData.loadState(connection);
        assertEquals(0.006, m.get("rate"));
        assertEquals(150, m.get("treasury"));
        assertFalse(m.containsKey("nan"));

        assertTrue(EconomyData.withdrawFromState(connection, "treasury", 100, 25));
        assertFalse(EconomyData.withdrawFromState(connection, "treasury", 30, 25));
        assertEquals(50, EconomyData.loadState(connection).get("treasury"));
        assertFalse(EconomyData.withdrawFromState(connection, "missing", 1, 0));
    }

    @Test
    @DisplayName("Snapshots round-trip every field and thin to the requested size")
    void snapshots() throws SQLException {
        for (int i = 0; i < 100; i++) EconomyData.insertSnapshot(connection, snap(i * 1000L, 100 + i));

        MacroSnapshot latest = EconomyData.latestSnapshot(connection);
        assertEquals(snap(99_000, 199), latest);

        List<MacroSnapshot> thin = EconomyData.loadSnapshots(connection, 0, 10);
        assertEquals(10, thin.size());
        assertEquals(0, thin.get(0).timestamp());
        assertEquals(99_000, thin.get(9).timestamp());

        assertEquals(50, EconomyData.loadSnapshots(connection, 50_000, 0).size());

        double[] near = EconomyData.cpiNear(connection, 10_500);
        assertEquals(10_000, (long) near[0]);
        assertEquals(110, near[1], 1e-9);
        assertEquals(0, (long) EconomyData.cpiNear(connection, -5)[0]);
    }

    @Test
    void sectorsCodec() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("a;b=c", 1.5);
        m.put("d", 2.0);
        Map<String, Double> back = EconomyData.decodeSectors(EconomyData.encodeSectors(m));
        assertEquals(Map.of("abc", 1.5, "d", 2.0), back);
        assertTrue(EconomyData.decodeSectors(null).isEmpty());
        assertTrue(EconomyData.decodeSectors("junk;=;x=notanumber").isEmpty());
    }

    @Test
    @DisplayName("Money supply, wealth (balance + latest worth) and trade analytics")
    void analytics() throws SQLException {
        exec("INSERT INTO balances (uuid, balance) VALUES ('p1', 100), ('p2', 300)");
        exec("INSERT INTO portfolios_worth (uuid, day, worth) VALUES ('p1', 10, 50), ('p1', 12, 70), ('p3', 12, 5)");
        exec("INSERT INTO user_names (uuid, name) VALUES ('p1', 'Alice')");
        exec("INSERT INTO trade_log (uuid, day, date, identifier, amount, value, buy, discord) VALUES " +
                "('p1', 12, '2026-09-29 13:05:00', 'iron', 10, 100, 1, 0), " +
                "('p1', 12, '2026-09-29 13:45:00', 'iron', 5, 40, 0, 0), " +
                "('p2', 12, '2026-09-29 02:00:00', 'gold', 1, 500, 1, 0), " +
                "('p2', 1, '2026-01-01 02:00:00', 'gold', 1, 999, 1, 0)");

        EconomyData.MoneySupply ms = EconomyData.moneySupply(connection);
        assertEquals(400, ms.total(), 1e-9);
        assertEquals(2, ms.holders());

        Map<String, Double> w = EconomyData.wealthByPlayer(connection, 5);
        assertEquals(170, w.get("p1"), 1e-9);
        assertEquals(300, w.get("p2"), 1e-9);
        assertEquals(5, w.get("p3"), 1e-9);

        List<EconomyData.TraderRow> top = EconomyData.topTraders(connection, 10, 5);
        assertEquals("p2", top.get(0).uuid());
        assertEquals(500, top.get(0).volume(), 1e-9);
        assertEquals("Alice", top.get(1).name());
        assertEquals(2, top.get(1).trades());

        assertEquals(2, EconomyData.traderVolumes(connection, 10).length);

        EconomyData.ItemFlow iron = EconomyData.itemFlows(connection, 10).stream()
                .filter(f -> f.identifier().equals("iron")).findFirst().orElseThrow();
        assertEquals(100, iron.bought(), 1e-9);
        assertEquals(40, iron.sold(), 1e-9);
        assertEquals(10, iron.unitsBought());
        assertEquals(5, iron.unitsSold());

        double[][] hours = EconomyData.hourlyActivity(connection, 10);
        assertEquals(2, hours[13][0], 1e-9);
        assertEquals(140, hours[13][1], 1e-9);
        assertEquals(1, hours[2][0], 1e-9);
    }

    @Test
    void purge() throws SQLException {
        EconomyData.insertSnapshot(connection, snap(1000, 100));
        EconomyData.insertSnapshot(connection, snap(5000, 100));
        EconomyData.insertWindow(connection, 1000, "a", 1, 1, 0, 1);
        EconomyData.saveShock(connection, new EconomyData.ShockRow("s1", "BOOM", null, 0.1, 0, 2000));
        EconomyData.saveShock(connection, new EconomyData.ShockRow("s2", "GLUT", "ores", -0.1, 0, 9000));

        EconomyData.purge(connection, 3000);
        assertEquals(1, EconomyData.loadSnapshots(connection, 0, 0).size());
        assertEquals(0, EconomyData.sumWindows(connection, 0).trades());
        List<EconomyData.ShockRow> shocks = EconomyData.loadShocks(connection, 0);
        assertEquals(1, shocks.size());
        assertEquals("ores", shocks.get(0).category());
    }

    private void exec(String sql) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(sql)) { p.executeUpdate(); }
    }
}
