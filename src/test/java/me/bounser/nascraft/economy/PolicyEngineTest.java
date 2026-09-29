package me.bounser.nascraft.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PolicyEngineTest {

    private static final long HOUR = 3_600_000L;

    private static EconomySettings settings(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return EconomySettings.from(c);
    }

    private static PolicyEngine.Inputs inputs(long ts, double cpi, double cpiRef, double gdp, double money, int holders,
                                              double treasury, int online) {
        return new PolicyEngine.Inputs(ts, cpi, cpiRef, 24, gdp, money, holders, treasury, 0.3, 0,
                0, gdp / 2, gdp / 2, 10, online, Map.of());
    }

    @Test
    @DisplayName("Inflation above target pushes the policy rate above neutral and trims liquidity")
    void tightensOnInflation() throws Exception {
        EconomySettings s = settings("central-bank:\n  smoothing: 0\n");
        PolicyState st = new PolicyState();
        st.inflation = 0.01; // already running hot
        var r = PolicyEngine.step(st, inputs(10 * HOUR, 101, 100, 1000, 0, 0, 0, 0), s);

        assertTrue(r.state().policyRate > s.neutralRate + s.inflationTarget, "rate " + r.state().policyRate);
        assertTrue(r.state().liquidity < 1, "liquidity " + r.state().liquidity);
        assertTrue(r.state().liquidity >= s.minLiquidity);
    }

    @Test
    @DisplayName("Policy rate always stays within [min-rate, max-rate]")
    void rateClamped() throws Exception {
        EconomySettings s = settings("central-bank:\n  smoothing: 0\n  max-rate: 0.02\n  min-rate: 0.001\n");
        PolicyState hot = new PolicyState();
        hot.inflation = 0.4;
        assertEquals(0.02, PolicyEngine.step(hot, inputs(HOUR, 200, 100, 0, 0, 0, 0, 0), s).state().policyRate, 1e-12);

        PolicyState cold = new PolicyState();
        cold.inflation = -0.4;
        assertEquals(0.001, PolicyEngine.step(cold, inputs(HOUR, 50, 100, 0, 0, 0, 0, 0), s).state().policyRate, 1e-12);
    }

    @Test
    @DisplayName("Fixed mode ignores the Taylor rule")
    void fixedMode() throws Exception {
        EconomySettings s = settings("central-bank:\n  mode: fixed\n  fixed-rate: 0.007\n  smoothing: 0\n");
        PolicyState hot = new PolicyState();
        hot.inflation = 0.2;
        assertEquals(0.007, PolicyEngine.step(hot, inputs(HOUR, 150, 100, 0, 0, 0, 0, 0), s).state().policyRate, 1e-12);
    }

    @Test
    @DisplayName("A slump (negative output gap) lowers taxes; a boom raises them")
    void stabilizers() throws Exception {
        EconomySettings s = settings("treasury:\n  automatic-stabilizers:\n    smoothing: 0\n");
        PolicyState st = new PolicyState();
        st.trendOutput = 1000;

        var slump = PolicyEngine.step(st, inputs(HOUR, 100, 100, 500, 0, 0, 0, 0), s);
        assertTrue(slump.state().taxScale < 1);
        assertTrue(slump.snapshot().outputGap() < 0);
        assertTrue(slump.snapshot().recession());

        var boom = PolicyEngine.step(st, inputs(HOUR, 100, 100, 1500, 0, 0, 0, 0), s);
        assertTrue(boom.state().taxScale > 1);
        assertTrue(boom.state().taxScale <= s.maxTaxScale);
    }

    @Test
    @DisplayName("Price level follows money per holder, but never faster than the daily cap")
    void priceLevelCapped() throws Exception {
        EconomySettings s = settings("price-level:\n  max-change-per-day: 0.024\n");
        PolicyState st = new PolicyState();
        st.baseMoneySupply = 100; // per holder
        st.lastTick = 0;

        // Money per holder quadrupled → target level 2, but only one 5-minute tick passed.
        var r = PolicyEngine.step(st, inputs(1, 100, 100, 0, 400 * 10, 10, 0, 0), s);
        double maxStep = 0.024 * (s.tickSeconds / 3600.0) / 24;
        assertEquals(1 + maxStep, r.state().priceLevel, 1e-12);
    }

    @Test
    @DisplayName("New holders at the same wealth don't move the price level")
    void priceLevelPerCapita() throws Exception {
        EconomySettings s = settings("");
        PolicyState st = new PolicyState();
        st.baseMoneySupply = 100;
        var r = PolicyEngine.step(st, inputs(HOUR, 100, 100, 0, 100 * 50, 50, 0, 0), s);
        assertEquals(1.0, r.state().priceLevel, 1e-12);
    }

    @Test
    @DisplayName("UBI pays a share of the surplus above reserve, capped per player")
    void ubiSizing() throws Exception {
        EconomySettings s = settings("treasury:\n  reserve: 1000\n  ubi:\n    enabled: true\n    share-of-surplus: 0.1\n    max-per-player: 50\n    min-online: 2\n");
        PolicyState st = new PolicyState();

        assertEquals(10, PolicyEngine.step(st, inputs(HOUR, 100, 100, 0, 0, 0, 2000, 10), s).state().ubiPerCapita, 1e-9);
        assertEquals(50, PolicyEngine.step(st, inputs(HOUR, 100, 100, 0, 0, 0, 100000, 10), s).state().ubiPerCapita, 1e-9);
        assertEquals(0, PolicyEngine.step(st, inputs(HOUR, 100, 100, 0, 0, 0, 500, 10), s).state().ubiPerCapita, 1e-9);
        assertEquals(0, PolicyEngine.step(st, inputs(HOUR, 100, 100, 0, 0, 0, 2000, 1), s).state().ubiPerCapita, 1e-9);
    }

    @Test
    @DisplayName("Disabled subsystems leave every lever neutral")
    void disabledIsNeutral() throws Exception {
        EconomySettings s = settings("central-bank:\n  enabled: false\nprice-level:\n  enabled: false\ntreasury:\n  enabled: false\n");
        PolicyState st = new PolicyState();
        st.inflation = 0.3;
        st.trendOutput = 1000;
        var r = PolicyEngine.step(st, inputs(HOUR, 150, 100, 10, 1e9, 1, 1e9, 10), s);
        assertTrue(Double.isNaN(r.state().policyRate));
        assertEquals(1, r.state().liquidity);
        assertEquals(1, r.state().taxScale);
        assertEquals(1, r.state().priceLevel);
        assertEquals(0, r.state().ubiPerCapita);
    }

    @Test
    void stateRoundTrips() {
        PolicyState st = new PolicyState();
        st.policyRate = 0.006;
        st.priceLevel = 1.2;
        st.lastTick = 123456789L;
        PolicyState back = PolicyState.fromMap(st.toMap());
        assertEquals(0.006, back.policyRate);
        assertEquals(1.2, back.priceLevel);
        assertEquals(123456789L, back.lastTick);
    }
}
