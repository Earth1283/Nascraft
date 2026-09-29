package me.bounser.nascraft.economy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent policy state carried between engine ticks. Stored as key/value
 * rows so new fields can be added without schema changes.
 */
public final class PolicyState {

    public double policyRate = Double.NaN;
    public double liquidity = 1.0;
    public double taxScale = 1.0;
    public double priceLevel = 1.0;
    /** Money per holder the price level is measured against (first observation). */
    public double baseMoneySupply = 0;
    /** Slow EMA of real GDP: the economy's potential output. */
    public double trendOutput = Double.NaN;
    public double outputGap = 0;
    public double inflation = 0;
    public double ubiPerCapita = 0;
    public long lastTick = 0;

    public Map<String, Double> toMap() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("policy_rate", policyRate);
        m.put("liquidity", liquidity);
        m.put("tax_scale", taxScale);
        m.put("price_level", priceLevel);
        m.put("base_money_supply", baseMoneySupply);
        m.put("trend_output", trendOutput);
        m.put("output_gap", outputGap);
        m.put("inflation", inflation);
        m.put("ubi_per_capita", ubiPerCapita);
        m.put("last_tick", (double) lastTick);
        return m;
    }

    public static PolicyState fromMap(Map<String, Double> m) {
        PolicyState s = new PolicyState();
        s.policyRate = m.getOrDefault("policy_rate", Double.NaN);
        s.liquidity = m.getOrDefault("liquidity", 1.0);
        s.taxScale = m.getOrDefault("tax_scale", 1.0);
        s.priceLevel = m.getOrDefault("price_level", 1.0);
        s.baseMoneySupply = m.getOrDefault("base_money_supply", 0.0);
        s.trendOutput = m.getOrDefault("trend_output", Double.NaN);
        s.outputGap = m.getOrDefault("output_gap", 0.0);
        s.inflation = m.getOrDefault("inflation", 0.0);
        s.ubiPerCapita = m.getOrDefault("ubi_per_capita", 0.0);
        s.lastTick = m.getOrDefault("last_tick", 0.0).longValue();
        return s;
    }

    public PolicyState copy() { return fromMap(toMap()); }
}
