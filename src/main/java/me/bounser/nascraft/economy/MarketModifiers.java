package me.bounser.nascraft.economy;

/**
 * Economy-wide knobs read on the pricing hot path. Plain volatile fields so a
 * read costs nothing and needs no locking; the engine is the only writer.
 *
 * With every knob at its neutral value, prices and taxes are bit-for-bit what
 * they were before the economy engine existed.
 */
public final class MarketModifiers {

    private MarketModifiers() {}

    /** Nominal price level from the money supply (1 = baseline). Scales every item's base value. */
    private static volatile double priceLevel = 1.0;

    /** Fiscal stabilizer: multiplies the tax part of both buy and sell spreads. */
    private static volatile double taxScale = 1.0;

    /** Central-bank liquidity lever: multiplies sell payouts (money creation). */
    private static volatile double liquidity = 1.0;

    /** Round trips must always lose at least this fraction, whatever the knobs say. */
    private static volatile double minSpread = 0.005;

    private static volatile boolean neutral = true;

    public static double priceLevel() { return priceLevel; }
    public static double taxScale() { return taxScale; }
    public static double liquidity() { return liquidity; }
    public static double minSpread() { return minSpread; }

    public static void set(double priceLevel, double taxScale, double liquidity, double minSpread) {
        MarketModifiers.priceLevel = sane(priceLevel);
        MarketModifiers.taxScale = Math.max(0, sane(taxScale));
        MarketModifiers.liquidity = Math.max(0, sane(liquidity));
        MarketModifiers.minSpread = Math.max(0, minSpread);
        neutral = MarketModifiers.priceLevel == 1.0 && MarketModifiers.taxScale == 1.0 && MarketModifiers.liquidity == 1.0;
    }

    public static void reset() { set(1, 1, 1, 0.005); }

    public static boolean isNeutral() { return neutral; }

    /**
     * Effective buy multiplier (e.g. 1.04 for a 4% buy tax).
     * @param extraSpread per-item volatility premium, added on top of the scaled tax
     */
    public static float effectiveBuy(float baseBuy, float baseSell, double extraSpread) {
        if (neutral && extraSpread == 0) return baseBuy;
        return (float) guarded(baseBuy, baseSell, extraSpread)[0];
    }

    public static float effectiveSell(float baseBuy, float baseSell, double extraSpread) {
        if (neutral && extraSpread == 0) return baseSell;
        return (float) guarded(baseBuy, baseSell, extraSpread)[1];
    }

    private static double[] guarded(float baseBuy, float baseSell, double extraSpread) {
        double buy = 1 + (baseBuy - 1) * taxScale + extraSpread / 2;
        double sell = (1 - (1 - baseSell) * taxScale - extraSpread / 2) * liquidity;
        sell = Math.max(0, sell);
        buy = Math.max(0, buy);

        // Exploit guard: never let buy-low/sell-high round trips break even,
        // whichever way the fiscal and monetary levers are pushed. Only enforced
        // when the configured item actually has a spread to protect.
        if (baseBuy > baseSell && buy > 0) {
            double maxSell = buy * (1 - minSpread);
            if (sell > maxSell) sell = maxSell;
        }
        return new double[] { buy, sell };
    }

    private static double sane(double v) { return Double.isFinite(v) && v > 0 ? v : 1.0; }
}
