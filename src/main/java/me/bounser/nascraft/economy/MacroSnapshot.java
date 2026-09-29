package me.bounser.nascraft.economy;

import java.util.Map;

/**
 * One reading of the whole economy. Money amounts are in the default currency;
 * rates and inflation are per-day fractions; flows are trailing 24h sums.
 */
public record MacroSnapshot(
        long timestamp,
        double cpi,
        double inflation,
        double gdp,
        double realGdp,
        double moneySupply,
        double velocity,
        double outputGap,
        EconomyMath.Phase phase,
        boolean recession,
        double policyRate,
        double liquidity,
        double taxScale,
        double priceLevel,
        double treasury,
        double ubiPerCapita,
        double gini,
        double outstandingDebt,
        double taxes,
        double moneyCreated,
        double moneyDestroyed,
        long trades,
        Map<String, Double> sectorIndices
) {
    /** Net money issued by the market over the window (sell payouts minus buy costs). */
    public double netIssuance() { return moneyCreated - moneyDestroyed; }

    public static MacroSnapshot empty() {
        return new MacroSnapshot(System.currentTimeMillis(), 100, 0, 0, 0, 0, 0, 0,
                EconomyMath.Phase.EXPANSION, false, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }
}
