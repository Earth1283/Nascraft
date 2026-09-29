package me.bounser.nascraft.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EconomySettingsTest {

    private static EconomySettings bundled() throws Exception {
        try (Reader r = new InputStreamReader(EconomySettingsTest.class.getResourceAsStream("/economy.yml"), StandardCharsets.UTF_8)) {
            return EconomySettings.from(YamlConfiguration.loadConfiguration(r));
        }
    }

    @Test
    @DisplayName("Bundled economy.yml parses and agrees with the code defaults")
    void bundledMatchesDefaults() throws Exception {
        EconomySettings file = bundled();
        EconomySettings code = EconomySettings.defaults();

        assertEquals(code.tickSeconds, file.tickSeconds);
        assertEquals(code.inflationTarget, file.inflationTarget);
        assertEquals(code.neutralRate, file.neutralRate);
        assertEquals(code.rateSmoothing, file.rateSmoothing);
        assertEquals(code.minLiquidity, file.minLiquidity);
        assertEquals(code.priceLevelElasticity, file.priceLevelElasticity);
        assertEquals(code.treasuryReserve, file.treasuryReserve);
        assertEquals(code.ubiEnabled, file.ubiEnabled);
        assertEquals(code.meanReversionHalfLifeHours, file.meanReversionHalfLifeHours);
        assertEquals(code.categorySpillover, file.categorySpillover);
        assertEquals(code.shocksPerDay, file.shocksPerDay);
        assertEquals(code.minSpread, file.minSpread);
        assertEquals(code.historyMaxPoints, file.historyMaxPoints);
        assertEquals(2, file.wealthTaxBrackets.size());
        assertFalse(file.spillovers.isEmpty());
    }

    @Test
    @DisplayName("Item overrides beat category overrides, which beat globals")
    void overridePrecedence() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("""
                micro:
                  mean-reversion:
                    half-life-hours: 100
                  excluded-items: [Bedrock]
                  category-overrides:
                    ores:
                      mean-reversion-half-life-hours: 200
                      spillover: 0.3
                      dynamic-spread: false
                  item-overrides:
                    diamond:
                      mean-reversion-half-life-hours: 400
                      dynamic-spread: true
                business-cycle:
                  shocks:
                    excluded-categories: [food]
                """);
        EconomySettings s = EconomySettings.from(c);

        assertEquals(400, s.halfLifeFor("diamond", "ores"));
        assertEquals(200, s.halfLifeFor("iron", "ores"));
        assertEquals(100, s.halfLifeFor("wheat", "food"));
        assertEquals(0.3, s.categorySpilloverFor("ores"));
        assertEquals(s.categorySpillover, s.categorySpilloverFor("food"));
        assertTrue(s.dynamicSpreadFor("diamond", "ores"));
        assertFalse(s.dynamicSpreadFor("iron", "ores"));
        assertFalse(s.shocksAllowedFor("wheat", "food"));
        assertTrue(s.shocksAllowedFor("iron", "ores"));
        assertTrue(s.isExcluded("bedrock"));
    }

    @Test
    void wealthTaxIsMarginal() throws Exception {
        EconomySettings s = bundled();
        assertEquals(0, EconomyEngine.wealthTaxFor(50_000, s.wealthTaxBrackets), 1e-9);
        assertEquals(100, EconomyEngine.wealthTaxFor(200_000, s.wealthTaxBrackets), 1e-9);
        // 900k × 0.1% + 1M × 0.3%
        assertEquals(900 + 3000, EconomyEngine.wealthTaxFor(2_000_000, s.wealthTaxBrackets), 1e-6);
    }
}
