package me.bounser.nascraft.economy;

import me.bounser.nascraft.market.support.MarketTestFixture;
import me.bounser.nascraft.market.unit.Price;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MicroDynamicsTest extends MarketTestFixture {

    private static final long HOUR = 3_600_000L;

    @AfterEach
    void resetModifiers() { MarketModifiers.reset(); }

    private static EconomySettings settings(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("micro:\n  dynamic-spread:\n    enabled: false\n" + yaml);
        return EconomySettings.from(c);
    }

    private Price price(String id) {
        return aPrice().identifier(id).initialValue(100f).elasticity(10f).noTaxes().precision(6).build();
    }

    @Test
    @DisplayName("Mean reversion halves the stock imbalance after one half-life")
    void meanReversion() throws Exception {
        EconomySettings s = settings("  mean-reversion:\n    half-life-hours: 10\n  spillovers:\n    enabled: false\n");
        Price p = price("a");
        p.setStock(400);
        MicroDynamics m = new MicroDynamics(s, new Random(1));
        m.setAssets(List.of(new MicroDynamics.Asset("a", "cat", p)));

        m.tick(0, 10 * HOUR);
        assertEquals(200, p.getStock(), 0.01);
    }

    @Test
    @DisplayName("Per-item half-life override and exclusion are honoured")
    void overrides() throws Exception {
        EconomySettings s = settings("""
                  mean-reversion:
                    half-life-hours: 10
                  excluded-items: [frozen]
                  item-overrides:
                    slow:
                      mean-reversion-half-life-hours: 20
                """);
        Price slow = price("slow"), frozen = price("frozen");
        slow.setStock(400);
        frozen.setStock(400);
        MicroDynamics m = new MicroDynamics(s, new Random(1));
        m.setAssets(List.of(new MicroDynamics.Asset("slow", "c", slow), new MicroDynamics.Asset("frozen", "c", frozen)));

        m.tick(0, 20 * HOUR);
        assertEquals(200, slow.getStock(), 0.01);
        assertEquals(400, frozen.getStock(), 1e-6);
    }

    @Test
    @DisplayName("Buying one item nudges same-category peers the same direction")
    void categorySpillover() throws Exception {
        EconomySettings s = settings("  mean-reversion:\n    enabled: false\n  spillovers:\n    same-category: 0.1\n");
        Price a = price("a"), b = price("b"), other = price("x");
        MicroDynamics m = new MicroDynamics(s, new Random(1));
        m.setAssets(List.of(new MicroDynamics.Asset("a", "ores", a), new MicroDynamics.Asset("b", "ores", b),
                new MicroDynamics.Asset("x", "food", other)));

        double before = b.getValue();
        a.changeStock(-200);
        double aMove = Math.log(a.getValue() / 100);
        m.onTrade("a", -200);
        m.tick(0, HOUR);

        assertEquals(aMove * 0.1, Math.log(b.getValue() / before), 1e-4);
        assertEquals(100, other.getValue(), 1e-4);
    }

    @Test
    @DisplayName("Explicit link overrides the category coefficient")
    void explicitLink() throws Exception {
        EconomySettings s = settings("""
                  mean-reversion:
                    enabled: false
                  spillovers:
                    same-category: 0.1
                    links:
                      a:
                        b: -0.5
                """);
        Price a = price("a"), b = price("b");
        MicroDynamics m = new MicroDynamics(s, new Random(1));
        m.setAssets(List.of(new MicroDynamics.Asset("a", "ores", a), new MicroDynamics.Asset("b", "ores", b)));

        a.changeStock(-200);
        double aMove = Math.log(a.getValue() / 100);
        m.onTrade("a", -200);
        m.tick(0, HOUR);
        assertEquals(-0.5 * aMove, Math.log(b.getValue() / 100), 1e-4);
    }

    @Test
    @DisplayName("Recipe arbitrage pulls an overpriced product toward ingredient cost")
    void recipeArbitrage() throws Exception {
        EconomySettings s = settings("""
                  mean-reversion:
                    enabled: false
                  spillovers:
                    enabled: false
                  recipes:
                    upper-band: 0.1
                    convergence: 1.0
                    list:
                      block:
                        output: 1
                        ingredients:
                          ingot: 9
                """);
        Price ingot = aPrice().identifier("ingot").initialValue(10f).elasticity(10f).noTaxes().precision(6).build();
        Price block = aPrice().identifier("block").initialValue(200f).elasticity(10f).noTaxes().precision(6).build();
        MicroDynamics m = new MicroDynamics(s, new Random(1));
        m.setAssets(List.of(new MicroDynamics.Asset("ingot", "c", ingot), new MicroDynamics.Asset("block", "c", block)));

        m.tick(0, 60_000);
        assertEquals(90 * 1.1, block.getValue(), 0.05);

        // Inside the band nothing moves.
        double settled = block.getStock();
        m.tick(60_000, 120_000);
        assertEquals(settled, block.getStock(), 1e-3);
    }

    @Test
    @DisplayName("A shock applies its full impact over its lifetime, only to its category")
    void shockImpact() throws Exception {
        EconomySettings s = settings("  mean-reversion:\n    enabled: false\n  spillovers:\n    enabled: false\n");
        Price hit = price("a"), spared = price("b");
        MicroDynamics m = new MicroDynamics(s, new Random(1));
        m.setAssets(List.of(new MicroDynamics.Asset("a", "ores", hit), new MicroDynamics.Asset("b", "food", spared)));
        m.addShock(new Shock("t", Shock.Kind.SHORTAGE, "ores", Math.log(1.2), 0, 4 * HOUR));

        for (long t = 0; t < 4 * HOUR; t += HOUR) m.tick(t, t + HOUR);
        assertEquals(120, hit.getValue(), 0.05);
        assertEquals(100, spared.getValue(), 1e-4);
        assertTrue(m.activeShocks(5 * HOUR).isEmpty());
    }

    @Test
    @DisplayName("Random shocks respect the concurrency cap and weights")
    void shockRolls() throws Exception {
        EconomySettings s = settings("""
                business-cycle:
                  shocks:
                    per-day: 100000
                    max-concurrent: 2
                    market-wide-chance: 0
                    weights: { shortage: 0, glut: 1 }
                """);
        MicroDynamics m = new MicroDynamics(s, new Random(7));
        m.setAssets(List.of(new MicroDynamics.Asset("a", "ores", price("a"))));

        int started = 0;
        for (int i = 0; i < 10; i++) if (m.maybeStartShock(0, 1) != null) started++;
        assertEquals(2, started);
        for (Shock sh : m.activeShocks(1)) assertEquals(Shock.Kind.GLUT, sh.kind());
    }

    @Test
    void shockImpactBetweenClips() {
        Shock sh = new Shock("s", Shock.Kind.BOOM, null, 1.0, 100, 200);
        assertEquals(0.5, sh.impactBetween(0, 150), 1e-12);
        assertEquals(1.0, sh.impactBetween(0, 1000), 1e-12);
        assertEquals(0, sh.impactBetween(300, 400), 1e-12);
        assertTrue(sh.appliesTo("anything"));
    }

    @Test
    void volatilitySpread() {
        assertEquals(0, MicroDynamics.spreadFor(2, 0.05, null));
        List<Double> wild = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) wild.add(i % 2 == 0 ? 100.0 : 130.0);
        assertEquals(0.05, MicroDynamics.spreadFor(2, 0.05, wild), 1e-12);
    }
}
