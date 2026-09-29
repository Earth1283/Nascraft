package me.bounser.nascraft.economy;

import java.util.Map;

import static me.bounser.nascraft.economy.EconomyMath.*;

/**
 * The macro loop as a pure function: measurements in, new policy state and a
 * snapshot out. The Bukkit-facing {@link EconomyEngine} gathers the inputs and
 * applies the outputs; everything in between lives here.
 */
public final class PolicyEngine {

    private PolicyEngine() {}

    public record Inputs(
            long timestamp,
            double cpi,
            double cpiReference,
            double cpiReferenceHours,
            double gdp,
            double moneySupply,
            int moneyHolders,
            double treasury,
            double gini,
            double outstandingDebt,
            double taxes,
            double moneyCreated,
            double moneyDestroyed,
            long trades,
            int activePlayers,
            Map<String, Double> sectorIndices
    ) {}

    public record Result(PolicyState state, MacroSnapshot snapshot) {}

    public static Result step(PolicyState previous, Inputs in, EconomySettings s) {
        PolicyState next = previous.copy();

        double tickHours = s.tickSeconds / 3600.0;
        double dt = previous.lastTick > 0
                ? clamp((in.timestamp() - previous.lastTick) / 3_600_000.0, tickHours / 10, 24)
                : tickHours;
        next.lastTick = in.timestamp();

        // Inflation: compounded per-day change of the CPI, smoothed.
        double raw = in.cpiReferenceHours() >= 1
                ? dailyInflation(in.cpiReference(), in.cpi(), in.cpiReferenceHours())
                : 0;
        raw = clamp(raw, -0.5, 0.5);
        next.inflation = ema(previous.inflation, raw, emaAlpha(dt, s.inflationSmoothingHours));

        // Output: real GDP against its slow-moving trend (potential output).
        double deflator = in.cpi() > 0 ? in.cpi() / 100.0 : 1;
        double realGdp = in.gdp() / deflator;
        double trend = previous.trendOutput;
        double gap = (Double.isFinite(trend) && trend > 0) ? clamp((realGdp - trend) / trend, -1, 2) : 0;
        next.trendOutput = Double.isFinite(trend) ? ema(trend, realGdp, emaAlpha(dt, s.trendHalfLifeHours)) : realGdp;
        next.outputGap = gap;
        Phase phase = phase(gap, gap - previous.outputGap);
        boolean recession = gap <= s.recessionGap;

        // Central bank: Taylor rule for the loan rate, liquidity lever on payouts.
        double rho = s.rateSmoothing;
        if (s.cbEnabled) {
            double target = s.cbFixedMode
                    ? clamp(s.cbFixedRate, s.minRate, s.maxRate)
                    : clamp(taylorRate(s.neutralRate, next.inflation, s.inflationTarget,
                            s.inflationWeight, gap, s.outputWeight), s.minRate, s.maxRate);
            next.policyRate = Double.isFinite(previous.policyRate)
                    ? rho * previous.policyRate + (1 - rho) * target
                    : target;

            if (s.liquidityEnabled) {
                double liq = clamp(1 - s.liquiditySensitivity * (next.inflation - s.inflationTarget),
                        s.minLiquidity, s.maxLiquidity);
                next.liquidity = rho * previous.liquidity + (1 - rho) * liq;
            } else next.liquidity = 1;
        } else {
            next.policyRate = Double.NaN;
            next.liquidity = 1;
        }

        // Fiscal automatic stabilizers: cheaper trading in a slump, dearer in a boom.
        if (s.treasuryEnabled && s.stabilizersEnabled) {
            double ts = clamp(1 + s.stabilizerSensitivity * gap, s.minTaxScale, s.maxTaxScale);
            double sr = s.stabilizerSmoothing;
            next.taxScale = sr * previous.taxScale + (1 - sr) * ts;
        } else next.taxScale = 1;

        // Quantity theory anchor: more money per head chasing the same goods lifts
        // nominal prices. Per-capita, so newly tracked players don't read as printing.
        double moneyPerHolder = in.moneyHolders() > 0 ? in.moneySupply() / in.moneyHolders() : 0;
        if (s.priceLevelEnabled && moneyPerHolder > 0) {
            if (next.baseMoneySupply <= 0) next.baseMoneySupply = moneyPerHolder;
            double target = clamp(Math.pow(moneyPerHolder / next.baseMoneySupply, s.priceLevelElasticity),
                    s.minPriceLevel, s.maxPriceLevel);
            double maxStep = s.maxPriceLevelStepPerDay * dt / 24;
            double level = previous.priceLevel > 0 && Double.isFinite(previous.priceLevel) ? previous.priceLevel : 1;
            double ratio = clamp(target / level, 1 - maxStep, 1 + maxStep);
            next.priceLevel = clamp(level * ratio, s.minPriceLevel, s.maxPriceLevel);
        } else if (!s.priceLevelEnabled) {
            next.priceLevel = 1;
        }

        // UBI sizing: a share of the treasury surplus, boosted in recessions.
        if (s.treasuryEnabled && s.ubiEnabled && in.activePlayers() >= s.ubiMinOnline) {
            double surplus = Math.max(0, in.treasury() - s.treasuryReserve);
            double pool = surplus * s.ubiShareOfSurplus * (recession ? 1 + s.ubiRecessionBoost : 1);
            next.ubiPerCapita = Math.min(s.ubiMaxPerPlayer, pool / in.activePlayers());
        } else next.ubiPerCapita = 0;

        double velocity = in.moneySupply() > 0 ? in.gdp() / in.moneySupply() : 0;

        MacroSnapshot snap = new MacroSnapshot(
                in.timestamp(), in.cpi(), next.inflation, in.gdp(), realGdp, in.moneySupply(), velocity,
                gap, phase, recession,
                Double.isFinite(next.policyRate) ? next.policyRate : 0,
                next.liquidity, next.taxScale, next.priceLevel,
                in.treasury(), next.ubiPerCapita, in.gini(), in.outstandingDebt(),
                in.taxes(), in.moneyCreated(), in.moneyDestroyed(), in.trades(),
                in.sectorIndices());

        return new Result(next, snap);
    }
}
