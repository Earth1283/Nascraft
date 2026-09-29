package me.bounser.nascraft.economy;

import me.bounser.nascraft.market.unit.Price;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MicroDynamics {
    public record Asset(String id, String category, Price price) {}

    private final Map<String, Double> pendingLog = new ConcurrentHashMap<>();
    private final List<Shock> shocks = new CopyOnWriteArrayList<>();
    private final Random random;

    private volatile EconomySettings settings;
    private volatile Map<String, List<Asset>> byCategory = Map.of();
    private volatile Map<String, Asset> byId = Map.of();

    public MicroDynamics(EconomySettings settings, Random random) {
        this.settings = settings;
        this.random = random;
    }

    public void setSettings(EconomySettings settings) { this.settings = settings; }

    public void setAssets(Collection<Asset> assets) {
        Map<String, List<Asset>> cats = new HashMap<>();
        Map<String, Asset> ids = new HashMap<>();
        for (Asset a : assets) {
            ids.put(a.id().toLowerCase(), a);
            if (a.category() != null) cats.computeIfAbsent(a.category(), k -> new ArrayList<>()).add(a);
        }
        byCategory = cats;
        byId = ids;
    }

    public Collection<Asset> assets() { return byId.values(); }

    public void onTrade(String id, double stockChange) {
        EconomySettings s = settings;
        if (!s.spilloverEnabled || stockChange == 0) return;
        Asset src = byId.get(id.toLowerCase());
        if (src == null || s.isExcluded(src.id())) return;
        double stockPerLog = src.price().stockForLogChange(1);
        if (stockPerLog == 0) return;
        double srcLog = stockChange / stockPerLog;

        Map<String, Double> links = s.spillovers.get(src.id().toLowerCase());
        if (links != null) {
            for (var e : links.entrySet()) {
                if (byId.containsKey(e.getKey()) && !s.isExcluded(e.getKey()))
                    pendingLog.merge(e.getKey(), e.getValue() * srcLog, Double::sum);
            }
        }
        double catSpill = src.category() == null ? 0 : s.categorySpilloverFor(src.category());
        if (catSpill != 0) {
            List<Asset> peers = byCategory.get(src.category());
            if (peers != null) for (Asset peer : peers) {
                String pid = peer.id().toLowerCase();
                if (peer == src || s.isExcluded(pid) || (links != null && links.containsKey(pid))) continue;
                pendingLog.merge(pid, catSpill * srcLog, Double::sum);
            }
        }
    }

    public List<Shock> activeShocks(long now) {
        List<Shock> out = new ArrayList<>();
        for (Shock s : shocks) if (s.isActive(now)) out.add(s);
        return out;
    }

    public void addShock(Shock shock) { shocks.add(shock); }

    public Shock maybeStartShock(long now, double dtHours) {
        EconomySettings s = settings;
        if (!s.shocksEnabled || s.shocksPerDay <= 0) return null;
        if (activeShocks(now).size() >= s.maxConcurrentShocks) return null;
        double p = 1 - Math.exp(-s.shocksPerDay * dtHours / 24);
        if (random.nextDouble() >= p) return null;

        List<String> cats = new ArrayList<>();
        for (String cat : byCategory.keySet()) if (!s.shockExcludedCategories.contains(cat)) cats.add(cat);
        boolean marketWide = cats.isEmpty() || random.nextDouble() < s.marketWideChance;
        String category = marketWide ? null : cats.get(random.nextInt(cats.size()));

        double up = marketWide ? s.weightBoom : s.weightShortage;
        double down = marketWide ? s.weightSlump : s.weightGlut;
        if (up + down <= 0) return null;
        boolean rising = random.nextDouble() * (up + down) < up;

        double magnitude = s.shockMinMagnitude + random.nextDouble() * (s.shockMaxMagnitude - s.shockMinMagnitude);
        double log = Math.log(1 + magnitude) * (rising ? 1 : -1);
        double hours = s.shockMinHours + random.nextDouble() * (s.shockMaxHours - s.shockMinHours);
        Shock shock = new Shock(Long.toString(now, 36) + Integer.toString(random.nextInt(1296), 36),
                Shock.Kind.of(marketWide, log), category, log, now, now + (long) (hours * 3_600_000));
        shocks.add(shock);
        return shock;
    }

    public int tick(long from, long now) {
        EconomySettings s = settings;
        double dtHours = Math.max(0, (now - from) / 3_600_000.0);

        shocks.removeIf(sh -> sh.end() <= from);
        List<Shock> live = new ArrayList<>(shocks);

        int touched = 0;
        for (Asset a : byId.values()) {
            Price p = a.price();
            if (p.getElasticity() == 0 || s.isExcluded(a.id())) {
                pendingLog.remove(a.id().toLowerCase());
                continue;
            }
            String key = a.id().toLowerCase();

            double logPush = 0;
            Double spill = pendingLog.remove(key);
            if (spill != null) logPush += spill;
            if (s.shocksAllowedFor(a.id(), a.category()))
                for (Shock sh : live) if (sh.appliesTo(a.category())) logPush += sh.impactBetween(from, now);

            double reversion = s.meanReversionFor(a.id(), a.category())
                    ? 1 - Math.pow(0.5, dtHours / s.halfLifeFor(a.id(), a.category())) : 0;

            double delta = -p.getStock() * reversion + p.stockForLogChange(logPush);
            delta += recipePull(s, key, p);

            if (delta != 0 && Double.isFinite(delta)) {
                p.adjustStock(delta);
                touched++;
            }

            p.setExtraSpread(s.dynamicSpreadFor(a.id(), a.category())
                    ? spreadFor(s.spreadFactorFor(a.id(), a.category()), s.maxExtraSpread, p.getValuesPastHour()) : 0);
        }
        return touched;
    }

    static double spreadFor(double factor, double max, List<Double> hourValues) {
        if (hourValues == null) return 0;
        List<Double> snapshot;
        try {
            snapshot = new ArrayList<>(hourValues);
        } catch (RuntimeException e) {
            return 0;
        }
        snapshot.removeIf(v -> v == null);

        double hourly = EconomyMath.logReturnVolatility(snapshot) * Math.sqrt(60);
        return EconomyMath.clamp(hourly * factor, 0, max);
    }

    private double recipePull(EconomySettings s, String productId, Price product) {
        if (!s.recipesEnabled) return 0;
        EconomySettings.Recipe recipe = s.recipes.get(productId);
        if (recipe == null) return 0;

        double cost = 0;
        for (var ing : recipe.ingredients().entrySet()) {
            Asset in = byId.get(ing.getKey());
            if (in == null) return 0;
            cost += in.price().getValue() * ing.getValue();
        }
        cost /= recipe.outputAmount();
        if (cost <= 0) return 0;

        double value = product.getValue();
        double ceiling = cost * (1 + s.recipeUpperBand);
        double floor = cost * (1 - s.recipeLowerBand);
        double target;
        if (value > ceiling) target = ceiling;
        else if (floor > 0 && value < floor) target = floor;
        else return 0;

        return product.stockForLogChange(Math.log(target / value)) * s.recipeConvergence;
    }
}
