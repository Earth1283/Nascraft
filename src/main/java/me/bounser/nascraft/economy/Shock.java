package me.bounser.nascraft.economy;

/**
 * A temporary supply/demand disturbance. {@code logImpact} is the total change
 * in log price it pushes over its lifetime (+0.1 ≈ prices 10% higher), spread
 * evenly across the duration. Mean reversion then pulls prices back.
 *
 * @param category category identifier, or {@code null} for a market-wide shock
 */
public record Shock(String id, Kind kind, String category, double logImpact, long start, long end) {

    public enum Kind {
        /** Category supply dries up: prices rise. */
        SHORTAGE,
        /** Category oversupplied: prices fall. */
        GLUT,
        /** Market-wide demand surge. */
        BOOM,
        /** Market-wide demand collapse. */
        SLUMP;

        public static Kind of(boolean marketWide, double logImpact) {
            if (marketWide) return logImpact >= 0 ? BOOM : SLUMP;
            return logImpact >= 0 ? SHORTAGE : GLUT;
        }
    }

    public boolean appliesTo(String itemCategory) {
        return category == null || category.equals(itemCategory);
    }

    public boolean isActive(long now) { return now >= start && now < end; }

    /** Log-price push to apply over the interval (from, to], clipped to the shock's life. */
    public double impactBetween(long from, long to) {
        long a = Math.max(from, start), b = Math.min(to, end);
        if (b <= a || end <= start) return 0;
        return logImpact * (double) (b - a) / (end - start);
    }
}
