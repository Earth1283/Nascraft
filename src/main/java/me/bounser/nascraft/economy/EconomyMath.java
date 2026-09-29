package me.bounser.nascraft.economy;

import java.util.Arrays;
import java.util.List;

public final class EconomyMath {
    private EconomyMath() {}

    public static double emaAlpha(double stepHours, double halfLifeHours) {
        if (halfLifeHours <= 0) return 1;
        return 1 - Math.pow(0.5, stepHours / halfLifeHours);
    }

    public static double ema(double previous, double sample, double alpha) {
        if (!Double.isFinite(previous)) return sample;
        return previous + alpha * (sample - previous);
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public static double gini(double[] values) {
        int n = values.length;
        if (n < 2) return 0;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = Math.max(0, values[i]);
        Arrays.sort(v);
        double cumulative = 0, weighted = 0;
        for (int i = 0; i < n; i++) {
            cumulative += v[i];
            weighted += (i + 1) * v[i];
        }
        if (cumulative == 0) return 0;
        return (2 * weighted) / (n * cumulative) - (n + 1.0) / n;
    }

    public static double[] lorenz(double[] values, int points) {
        double[] out = new double[points + 1];
        int n = values.length;
        if (n == 0) {
            for (int i = 0; i <= points; i++) out[i] = (double) i / points;
            return out;
        }
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = Math.max(0, values[i]);
        Arrays.sort(v);
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + v[i];
        double total = prefix[n];
        for (int i = 0; i <= points; i++) {
            if (total == 0) { out[i] = (double) i / points; continue; }
            double pos = (double) i / points * n;
            int lo = (int) Math.floor(pos);
            double frac = pos - lo;
            double cum = prefix[Math.min(lo, n)] + (lo < n ? frac * v[lo] : 0);
            out[i] = cum / total;
        }
        return out;
    }

    public static double topShare(double[] values, double fraction) {
        int n = values.length;
        if (n == 0) return 0;
        double[] v = values.clone();
        Arrays.sort(v);
        int k = Math.max(1, (int) Math.ceil(n * fraction));
        double top = 0, total = 0;
        for (int i = 0; i < n; i++) {
            double x = Math.max(0, v[i]);
            total += x;
            if (i >= n - k) top += x;
        }
        return total == 0 ? 0 : top / total;
    }

    public static double hhi(double[] amounts) {
        double total = 0;
        for (double a : amounts) total += Math.max(0, a);
        if (total == 0) return 0;
        double h = 0;
        for (double a : amounts) {
            double s = Math.max(0, a) / total;
            h += s * s;
        }
        return h;
    }

    public static double taylorRate(double neutral, double inflation, double target,
                                    double inflationWeight, double gap, double outputWeight) {
        return neutral + inflation + inflationWeight * (inflation - target) + outputWeight * gap;
    }

    public static double dailyInflation(double indexThen, double indexNow, double hours) {
        if (indexThen <= 0 || indexNow <= 0 || hours <= 0) return 0;
        return Math.pow(indexNow / indexThen, 24.0 / hours) - 1;
    }

    public static double logReturnVolatility(List<Double> prices) {
        int n = prices.size();
        if (n < 3) return 0;
        double sum = 0, sumSq = 0;
        int count = 0;
        for (int i = 1; i < n; i++) {
            double a = prices.get(i - 1), b = prices.get(i);
            if (a <= 0 || b <= 0) continue;
            double r = Math.log(b / a);
            sum += r;
            sumSq += r * r;
            count++;
        }
        if (count < 2) return 0;
        double mean = sum / count;
        double var = (sumSq - count * mean * mean) / (count - 1);
        return var > 0 ? Math.sqrt(var) : 0;
    }

    public enum Phase { EXPANSION, SLOWDOWN, CONTRACTION, RECOVERY }

    public static Phase phase(double gap, double gapChange) {
        if (gap >= 0) return gapChange >= 0 ? Phase.EXPANSION : Phase.SLOWDOWN;
        return gapChange < 0 ? Phase.CONTRACTION : Phase.RECOVERY;
    }
}
