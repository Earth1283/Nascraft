package me.bounser.nascraft.economy;

public final class MarketModifiers {
    private MarketModifiers() {}

    private static volatile double priceLevel = 1.0;

    private static volatile double taxScale = 1.0;

    private static volatile double liquidity = 1.0;

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

    public static float effectiveBuy(float baseBuy, float baseSell, double extraSpread) {
        if (neutral && extraSpread == 0) return baseBuy;
        return (float) policyAdjusted(baseBuy, baseSell, extraSpread)[0];
    }

    public static float effectiveSell(float baseBuy, float baseSell, double extraSpread) {
        if (neutral && extraSpread == 0) return baseSell;
        return (float) policyAdjusted(baseBuy, baseSell, extraSpread)[1];
    }

    private static double[] policyAdjusted(float baseBuy, float baseSell, double extraSpread) {
        double buy = Math.max(0, 1 + (baseBuy - 1) * taxScale + extraSpread / 2);
        double sell = Math.max(0, (1 - (1 - baseSell) * taxScale - extraSpread / 2) * liquidity);
        boolean itemHasSpread = baseBuy > baseSell;
        if (itemHasSpread) sell = keepRoundTripUnprofitable(buy, sell);
        return new double[] { buy, sell };
    }

    private static double keepRoundTripUnprofitable(double buy, double sell) {
        return Math.min(sell, buy * (1 - minSpread));
    }

    private static double sane(double v) { return Double.isFinite(v) && v > 0 ? v : 1.0; }
}
