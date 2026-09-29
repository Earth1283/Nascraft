package me.bounser.nascraft.economy;

public record Shock(String id, Kind kind, String category, double logImpact, long start, long end) {
    public enum Kind {
        SHORTAGE, GLUT, BOOM, SLUMP;

        public static Kind of(boolean marketWide, double logImpact) {
            if (marketWide) return logImpact >= 0 ? BOOM : SLUMP;
            return logImpact >= 0 ? SHORTAGE : GLUT;
        }
    }

    public boolean appliesTo(String itemCategory) {
        return category == null || category.equals(itemCategory);
    }

    public boolean isActive(long now) { return now >= start && now < end; }

    public double impactBetween(long from, long to) {
        long a = Math.max(from, start), b = Math.min(to, end);
        if (b <= a || end <= start) return 0;
        return logImpact * (double) (b - a) / (end - start);
    }
}
