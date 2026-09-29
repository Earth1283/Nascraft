package me.bounser.nascraft.economy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of economy.yml. All rates are fractions (0.005 = 0.5%) and
 * inflation/interest are expressed per real-time day.
 */
public final class EconomySettings {

    public final boolean enabled;
    public final int tickSeconds;
    public final int microTickSeconds;
    public final int snapshotRetentionDays;

    // Inflation measurement
    public final double inflationSmoothingHours;
    public final double inflationReferenceHours;

    // Central bank
    public final boolean cbEnabled;
    public final boolean cbFixedMode;
    public final double cbFixedRate;
    public final boolean cbControlsLoans;
    public final double inflationTarget;
    public final double neutralRate;
    public final double inflationWeight;
    public final double outputWeight;
    public final double rateSmoothing;
    public final double minRate;
    public final double maxRate;
    public final boolean liquidityEnabled;
    public final double liquiditySensitivity;
    public final double minLiquidity;
    public final double maxLiquidity;

    // Price level anchoring (quantity theory)
    public final boolean priceLevelEnabled;
    public final double priceLevelElasticity;
    public final double maxPriceLevelStepPerDay;
    public final double minPriceLevel;
    public final double maxPriceLevel;

    // Treasury / fiscal
    public final boolean treasuryEnabled;
    public final double treasuryReserve;
    public final boolean stabilizersEnabled;
    public final double stabilizerSensitivity;
    public final double minTaxScale;
    public final double maxTaxScale;
    public final double stabilizerSmoothing;
    public final boolean ubiEnabled;
    public final String ubiPermission;
    public final int ubiMinOnline;
    public final int ubiIntervalMinutes;
    public final double ubiShareOfSurplus;
    public final double ubiMaxPerPlayer;
    public final double ubiRecessionBoost;
    public final boolean wealthTaxEnabled;
    public final List<Bracket> wealthTaxBrackets;
    public final int wealthTaxBatchSize;
    public final double wealthTaxMaxPerPlayer;

    // Micro
    public final boolean meanReversionEnabled;
    public final double meanReversionHalfLifeHours;
    public final boolean spilloverEnabled;
    public final double categorySpillover;
    public final Map<String, Map<String, Double>> spillovers;
    public final boolean recipesEnabled;
    public final double recipeUpperBand;
    public final double recipeLowerBand;
    public final double recipeConvergence;
    public final Map<String, Recipe> recipes;
    public final boolean dynamicSpreadEnabled;
    public final double spreadVolatilityFactor;
    public final double maxExtraSpread;
    public final double minSpread;
    public final List<String> excludedItems;
    public final Map<String, Override> categoryOverrides;
    public final Map<String, Override> itemOverrides;

    // Cycle / shocks
    public final double trendHalfLifeHours;
    public final double recessionGap;
    public final boolean shocksEnabled;
    public final double shocksPerDay;
    public final double shockMinMagnitude;
    public final double shockMaxMagnitude;
    public final double shockMinHours;
    public final double shockMaxHours;
    public final boolean announceShocks;
    public final double marketWideChance;
    public final int maxConcurrentShocks;
    public final List<String> shockExcludedCategories;
    public final double weightShortage, weightGlut, weightBoom, weightSlump;

    // Analytics
    public final int analyticsWindowDays;
    public final int giniLookbackDays;
    public final int historyMaxPoints;

    /**
     * Per-category or per-item tuning. Null fields fall through to the global value.
     */
    public record Override(Double halfLifeHours, Double spillover, Boolean shocks, Boolean dynamicSpread,
                           Double spreadFactor, Boolean meanReversion) {
        static Override read(ConfigurationSection s) {
            return new Override(
                    s.contains("mean-reversion-half-life-hours") ? s.getDouble("mean-reversion-half-life-hours") : null,
                    s.contains("spillover") ? s.getDouble("spillover") : null,
                    s.contains("shocks") ? s.getBoolean("shocks") : null,
                    s.contains("dynamic-spread") ? s.getBoolean("dynamic-spread") : null,
                    s.contains("spread-volatility-factor") ? s.getDouble("spread-volatility-factor") : null,
                    s.contains("mean-reversion") ? s.getBoolean("mean-reversion") : null);
        }
    }

    public record Bracket(double threshold, double dailyRate) {}

    public record Recipe(Map<String, Double> ingredients, double outputAmount) {}

    private EconomySettings(ConfigurationSection c) {
        enabled = c.getBoolean("enabled", true);
        tickSeconds = Math.max(30, c.getInt("tick-seconds", 300));
        microTickSeconds = Math.max(10, c.getInt("micro-tick-seconds", 60));
        snapshotRetentionDays = Math.max(1, c.getInt("snapshot-retention-days", 180));

        inflationSmoothingHours = Math.max(0.1, c.getDouble("inflation.smoothing-half-life-hours", 12));
        inflationReferenceHours = Math.max(1, c.getDouble("inflation.reference-window-hours", 24));

        cbEnabled = c.getBoolean("central-bank.enabled", true);
        cbFixedMode = "fixed".equalsIgnoreCase(c.getString("central-bank.mode", "taylor"));
        cbFixedRate = c.getDouble("central-bank.fixed-rate", 0.005);
        cbControlsLoans = c.getBoolean("central-bank.controls-loan-rate", true);
        inflationTarget = c.getDouble("central-bank.inflation-target", 0.001);
        neutralRate = c.getDouble("central-bank.neutral-rate", 0.004);
        inflationWeight = c.getDouble("central-bank.inflation-weight", 0.5);
        outputWeight = c.getDouble("central-bank.output-gap-weight", 0.005);
        rateSmoothing = clamp01(c.getDouble("central-bank.smoothing", 0.8));
        minRate = c.getDouble("central-bank.min-rate", 0.0005);
        maxRate = c.getDouble("central-bank.max-rate", 0.03);
        liquidityEnabled = c.getBoolean("central-bank.liquidity.enabled", true);
        liquiditySensitivity = c.getDouble("central-bank.liquidity.sensitivity", 10);
        minLiquidity = c.getDouble("central-bank.liquidity.min", 0.95);
        maxLiquidity = c.getDouble("central-bank.liquidity.max", 1.02);

        priceLevelEnabled = c.getBoolean("price-level.enabled", true);
        priceLevelElasticity = c.getDouble("price-level.money-elasticity", 0.5);
        maxPriceLevelStepPerDay = c.getDouble("price-level.max-change-per-day", 0.02);
        minPriceLevel = c.getDouble("price-level.min", 0.25);
        maxPriceLevel = c.getDouble("price-level.max", 4.0);

        treasuryEnabled = c.getBoolean("treasury.enabled", true);
        treasuryReserve = c.getDouble("treasury.reserve", 10000);
        stabilizersEnabled = c.getBoolean("treasury.automatic-stabilizers.enabled", true);
        stabilizerSensitivity = c.getDouble("treasury.automatic-stabilizers.sensitivity", 1.0);
        minTaxScale = c.getDouble("treasury.automatic-stabilizers.min-tax-scale", 0.5);
        maxTaxScale = c.getDouble("treasury.automatic-stabilizers.max-tax-scale", 1.5);
        stabilizerSmoothing = clamp01(c.getDouble("treasury.automatic-stabilizers.smoothing", 0.8));
        ubiEnabled = c.getBoolean("treasury.ubi.enabled", false);
        ubiPermission = c.getString("treasury.ubi.permission", "nascraft.economy.ubi");
        ubiMinOnline = Math.max(1, c.getInt("treasury.ubi.min-online", 1));
        ubiIntervalMinutes = Math.max(5, c.getInt("treasury.ubi.interval-minutes", 60));
        ubiShareOfSurplus = clamp01(c.getDouble("treasury.ubi.share-of-surplus", 0.1));
        ubiMaxPerPlayer = c.getDouble("treasury.ubi.max-per-player", 250);
        ubiRecessionBoost = c.getDouble("treasury.ubi.recession-boost", 1.0);
        wealthTaxEnabled = c.getBoolean("treasury.wealth-tax.enabled", false);
        List<Bracket> brackets = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("treasury.wealth-tax.brackets")) {
            Object t = m.get("above"), r = m.get("daily-rate");
            if (t instanceof Number tn && r instanceof Number rn)
                brackets.add(new Bracket(tn.doubleValue(), rn.doubleValue()));
        }
        brackets.sort((a, b) -> Double.compare(a.threshold, b.threshold));
        wealthTaxBrackets = Collections.unmodifiableList(brackets);
        wealthTaxBatchSize = Math.max(1, c.getInt("treasury.wealth-tax.batch-size", 25));
        wealthTaxMaxPerPlayer = c.getDouble("treasury.wealth-tax.max-per-player", -1);

        meanReversionEnabled = c.getBoolean("micro.mean-reversion.enabled", true);
        meanReversionHalfLifeHours = Math.max(1, c.getDouble("micro.mean-reversion.half-life-hours", 168));
        spilloverEnabled = c.getBoolean("micro.spillovers.enabled", true);
        categorySpillover = c.getDouble("micro.spillovers.same-category", 0.05);
        spillovers = readNested(c.getConfigurationSection("micro.spillovers.links"));
        recipesEnabled = c.getBoolean("micro.recipes.enabled", true);
        recipeUpperBand = c.getDouble("micro.recipes.upper-band", 0.15);
        recipeLowerBand = c.getDouble("micro.recipes.lower-band", 0.40);
        recipeConvergence = clamp01(c.getDouble("micro.recipes.convergence", 0.05));
        Map<String, Recipe> r = new LinkedHashMap<>();
        ConfigurationSection rs = c.getConfigurationSection("micro.recipes.list");
        if (rs != null) {
            for (String product : rs.getKeys(false)) {
                ConfigurationSection p = rs.getConfigurationSection(product);
                if (p == null) continue;
                Map<String, Double> ing = new LinkedHashMap<>();
                ConfigurationSection is = p.getConfigurationSection("ingredients");
                if (is != null) for (String k : is.getKeys(false)) ing.put(k.toLowerCase(), is.getDouble(k));
                if (!ing.isEmpty()) r.put(product.toLowerCase(), new Recipe(ing, Math.max(1, p.getDouble("output", 1))));
            }
        }
        recipes = Collections.unmodifiableMap(r);
        dynamicSpreadEnabled = c.getBoolean("micro.dynamic-spread.enabled", true);
        spreadVolatilityFactor = c.getDouble("micro.dynamic-spread.volatility-factor", 2.0);
        maxExtraSpread = c.getDouble("micro.dynamic-spread.max-extra", 0.05);
        minSpread = c.getDouble("micro.min-round-trip-spread", 0.005);
        List<String> ex = new ArrayList<>();
        for (String id : c.getStringList("micro.excluded-items")) ex.add(id.toLowerCase());
        excludedItems = Collections.unmodifiableList(ex);
        categoryOverrides = readOverrides(c.getConfigurationSection("micro.category-overrides"));
        itemOverrides = readOverrides(c.getConfigurationSection("micro.item-overrides"));

        trendHalfLifeHours = Math.max(1, c.getDouble("business-cycle.trend-half-life-hours", 72));
        recessionGap = c.getDouble("business-cycle.recession-gap", -0.15);
        shocksEnabled = c.getBoolean("business-cycle.shocks.enabled", true);
        shocksPerDay = Math.max(0, c.getDouble("business-cycle.shocks.per-day", 0.5));
        shockMinMagnitude = c.getDouble("business-cycle.shocks.min-magnitude", 0.05);
        shockMaxMagnitude = Math.max(shockMinMagnitude, c.getDouble("business-cycle.shocks.max-magnitude", 0.20));
        shockMinHours = Math.max(0.1, c.getDouble("business-cycle.shocks.min-hours", 2));
        shockMaxHours = Math.max(shockMinHours, c.getDouble("business-cycle.shocks.max-hours", 12));
        announceShocks = c.getBoolean("business-cycle.shocks.announce", true);
        marketWideChance = clamp01(c.getDouble("business-cycle.shocks.market-wide-chance", 0.2));
        maxConcurrentShocks = Math.max(1, c.getInt("business-cycle.shocks.max-concurrent", 3));
        List<String> sx = new ArrayList<>();
        for (String id : c.getStringList("business-cycle.shocks.excluded-categories")) sx.add(id);
        shockExcludedCategories = Collections.unmodifiableList(sx);
        weightShortage = Math.max(0, c.getDouble("business-cycle.shocks.weights.shortage", 1));
        weightGlut = Math.max(0, c.getDouble("business-cycle.shocks.weights.glut", 1));
        weightBoom = Math.max(0, c.getDouble("business-cycle.shocks.weights.boom", 1));
        weightSlump = Math.max(0, c.getDouble("business-cycle.shocks.weights.slump", 1));

        analyticsWindowDays = Math.max(1, c.getInt("analytics.window-days", 7));
        giniLookbackDays = Math.max(1, c.getInt("analytics.gini-portfolio-lookback-days", 30));
        historyMaxPoints = Math.max(10, c.getInt("analytics.history-max-points", 500));
    }

    private static Map<String, Override> readOverrides(ConfigurationSection s) {
        Map<String, Override> out = new LinkedHashMap<>();
        if (s == null) return out;
        for (String key : s.getKeys(false)) {
            ConfigurationSection inner = s.getConfigurationSection(key);
            if (inner != null) out.put(key.toLowerCase(), Override.read(inner));
        }
        return Collections.unmodifiableMap(out);
    }

    private Override itemOv(String id) { return id == null ? null : itemOverrides.get(id.toLowerCase()); }
    private Override catOv(String cat) { return cat == null ? null : categoryOverrides.get(cat.toLowerCase()); }

    public boolean isExcluded(String id) { return id != null && excludedItems.contains(id.toLowerCase()); }

    public boolean meanReversionFor(String id, String category) {
        Override i = itemOv(id), c = catOv(category);
        if (i != null && i.meanReversion() != null) return i.meanReversion();
        if (c != null && c.meanReversion() != null) return c.meanReversion();
        return meanReversionEnabled;
    }

    public double halfLifeFor(String id, String category) {
        Override i = itemOv(id), c = catOv(category);
        if (i != null && i.halfLifeHours() != null) return Math.max(1, i.halfLifeHours());
        if (c != null && c.halfLifeHours() != null) return Math.max(1, c.halfLifeHours());
        return meanReversionHalfLifeHours;
    }

    public double categorySpilloverFor(String category) {
        Override c = catOv(category);
        return c != null && c.spillover() != null ? c.spillover() : categorySpillover;
    }

    public boolean shocksAllowedFor(String id, String category) {
        if (category != null && shockExcludedCategories.contains(category)) return false;
        Override i = itemOv(id), c = catOv(category);
        if (i != null && i.shocks() != null) return i.shocks();
        if (c != null && c.shocks() != null) return c.shocks();
        return true;
    }

    public boolean dynamicSpreadFor(String id, String category) {
        Override i = itemOv(id), c = catOv(category);
        if (i != null && i.dynamicSpread() != null) return i.dynamicSpread();
        if (c != null && c.dynamicSpread() != null) return c.dynamicSpread();
        return dynamicSpreadEnabled;
    }

    public double spreadFactorFor(String id, String category) {
        Override i = itemOv(id), c = catOv(category);
        if (i != null && i.spreadFactor() != null) return i.spreadFactor();
        if (c != null && c.spreadFactor() != null) return c.spreadFactor();
        return spreadVolatilityFactor;
    }

    private static Map<String, Map<String, Double>> readNested(ConfigurationSection s) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        if (s == null) return out;
        for (String from : s.getKeys(false)) {
            ConfigurationSection inner = s.getConfigurationSection(from);
            if (inner == null) continue;
            Map<String, Double> m = new LinkedHashMap<>();
            for (String to : inner.getKeys(false)) m.put(to.toLowerCase(), inner.getDouble(to));
            out.put(from.toLowerCase(), m);
        }
        return Collections.unmodifiableMap(out);
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    public static EconomySettings from(ConfigurationSection section) {
        return new EconomySettings(section == null ? new YamlConfiguration() : section);
    }

    public static EconomySettings defaults() { return from(new YamlConfiguration()); }
}
