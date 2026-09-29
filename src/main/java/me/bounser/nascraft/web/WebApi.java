package me.bounser.nascraft.web;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.database.BaseDatabase;
import me.bounser.nascraft.database.Database;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.database.commands.EconomyData;
import me.bounser.nascraft.database.commands.resources.NormalisedDate;
import me.bounser.nascraft.database.commands.resources.Trade;
import me.bounser.nascraft.economy.EconomyEngine;
import me.bounser.nascraft.economy.EconomyMath;
import me.bounser.nascraft.economy.EconomySettings;
import me.bounser.nascraft.economy.MacroSnapshot;
import me.bounser.nascraft.economy.Shock;
import me.bounser.nascraft.managers.DebtManager;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import me.bounser.nascraft.managers.currencies.Currency;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.resources.Category;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.unit.Price;
import me.bounser.nascraft.market.unit.stats.Instant;
import me.bounser.nascraft.portfolio.Portfolio;
import me.bounser.nascraft.portfolio.PortfoliosManager;
import me.bounser.nascraft.scheduler.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class WebApi {
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final Nascraft plugin;
    private volatile WebSettings settings;
    private final TtlCache cache = new TtlCache(2048);

    public WebApi(Nascraft plugin, WebSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void setSettings(WebSettings settings) {
        this.settings = settings;
        cache.invalidate("");
    }

    private long shortTtl() { return settings.marketCacheSeconds * 1000L; }
    private long heavyTtl() { return settings.heavyCacheSeconds * 1000L; }

    private static Currency currency() { return CurrenciesManager.getInstance().getDefaultCurrency(); }

    private static long epoch(LocalDateTime t) { return t.atZone(ZONE).toInstant().toEpochMilli(); }

    public Payload config() {
        return cache.get("config", 60_000, () -> Payload.json(JsonOut.build(j -> {
            WebSettings s = settings;
            Currency c = currency();
            j.obj()
                .f("title", s.title)
                .f("accent", s.accent)
                .f("defaultMode", s.defaultMode)
                .f("lockMode", s.lockMode)
                .f("defaultTheme", s.defaultTheme)
                .f("defaultLanguage", s.defaultLanguage);
            j.name("languages").arr();
            for (String l : s.languages) j.val(l);
            j.endArr();
            j.name("pages").obj()
                .f("market", s.pageMarket).f("economy", s.pageEconomy && EconomyEngine.active())
                .f("analytics", s.pageAnalytics).f("leaderboard", s.pageLeaderboard)
                .f("tradesFeed", s.tradesFeed).end();
            j.f("trading", s.tradingEnabled).f("maxTradeAmount", s.maxTradeAmount)
             .f("loginCommand", "/" + s.loginCommand)
             .f("liveInterval", s.liveIntervalSeconds)
             .f("showWealth", s.showWealth);
            currencyJson(j, c);
            j.end();
        })));
    }

    private static void currencyJson(JsonOut j, Currency c) throws IOException {
        j.name("currency").obj();
        if (c != null) {
            String plain = c.getPlainFormat() == null ? "[AMOUNT]" : c.getPlainFormat().replaceAll("[§&][0-9a-fk-or]", "");
            j.f("id", c.getCurrencyIdentifier()).f("format", plain).f("decimals", c.getDecimalPrecission());
        }
        j.end();
    }

    public Payload market() {
        return cache.get("market", shortTtl(), () -> Payload.json(JsonOut.build(j -> {
            MarketManager m = MarketManager.getInstance();
            j.obj().f("ts", System.currentTimeMillis()).f("open", m.getActive()).f("cpi", m.getConsumerPriceIndex());
            j.name("categories").arr();
            for (Category c : m.getCategories()) {
                j.obj().f("id", c.getIdentifier()).f("name", c.getDisplayName()).f("items", c.getNumberOfItems()).end();
            }
            j.endArr();
            j.name("items").arr();
            for (Item item : m.getAllParentItems()) itemSummary(j, item);
            j.endArr();
            j.end();
        })));
    }

    private static void itemSummary(JsonOut j, Item item) throws IOException {
        Price p = item.getPrice();
        j.obj()
            .f("id", item.getIdentifier())
            .f("name", item.getName())
            .f("cat", item.getCategory() == null ? null : item.getCategory().getIdentifier())
            .f("mat", item.peekItemStack().getType().getKey().getKey())
            .f("price", p.getValue())
            .f("buy", p.getBuyPrice())
            .f("sell", p.getSellPrice())
            .f("ch1h", p.getValueChangeLastHour())
            .f("stock", p.getStock())
            .f("ops", item.getOperations())
            .f("spread", p.getBuyTaxMultiplier() - p.getSellTaxMultiplier());
        List<Double> hour = p.getValuesPastHour();
        j.name("spark").arr();
        if (hour != null) {
            List<Double> copy = new ArrayList<>(hour);
            for (int i = 0; i < copy.size(); i += 2) j.val(copy.get(i) == null ? 0 : copy.get(i));
            if (!copy.isEmpty()) j.val(p.getValue());
        }
        j.endArr();
        j.end();
    }

    public byte[] liveFrame() {
        return JsonOut.build(j -> {
            j.obj().f("ts", System.currentTimeMillis());
            j.name("p").obj();
            for (Item item : MarketManager.getInstance().getAllParentItems()) {
                Price p = item.getPrice();
                j.name(item.getIdentifier()).arr().val(p.getValue()).val(p.getBuyPrice()).val(p.getSellPrice())
                        .val(p.getValueChangeLastHour()).endArr();
            }
            j.end().end();
        });
    }

    public Payload item(String id) {
        Item item = MarketManager.getInstance().getItem(id);
        if (item == null || !item.isParent()) return null;
        return cache.get("item:" + id, shortTtl(), () -> Payload.json(JsonOut.build(j -> {
            Price p = item.getPrice();
            j.obj()
                .f("id", item.getIdentifier())
                .f("name", item.getName())
                .f("cat", item.getCategory() == null ? null : item.getCategory().getIdentifier())
                .f("catName", item.getCategory() == null ? null : item.getCategory().getDisplayName())
                .f("mat", item.peekItemStack().getType().getKey().getKey())
                .f("price", p.getValue())
                .f("buy", p.getBuyPrice())
                .f("sell", p.getSellPrice())
                .f("ch1h", p.getValueChangeLastHour())
                .f("initial", p.getInitialValue())
                .f("equilibrium", p.base())
                .f("stock", p.getStock())
                .f("elasticity", p.getElasticity())
                .f("noise", p.getNoiseIntensity())
                .f("support", p.getSupport())
                .f("resistance", p.getResistance())
                .f("buyTax", p.getBuyTaxMultiplier() - 1)
                .f("sellTax", 1 - p.getSellTaxMultiplier())
                .f("baseBuyTax", p.getBaseBuyTaxMultiplier() - 1)
                .f("baseSellTax", 1 - p.getBaseSellTaxMultiplier())
                .f("volatilitySpread", p.getExtraSpread())
                .f("high", p.getHistoricalHigh())
                .f("low", p.getHistoricalLow())
                .f("ops", item.getOperations())
                .f("restricted", item.isPriceRestricted());
            j.name("children").arr();
            for (Item child : item.getChilds())
                j.obj().f("id", child.getIdentifier()).f("name", child.getName()).f("multiplier", child.getMultiplier()).end();
            j.endArr();

            EconomyEngine engine = EconomyEngine.get();
            j.name("recipe");
            EconomySettings.Recipe recipe = engine == null ? null : engine.settings().recipes.get(id.toLowerCase());
            if (recipe == null) j.nul();
            else {
                j.obj().f("output", recipe.outputAmount()).name("ingredients").arr();
                for (var e : recipe.ingredients().entrySet()) j.obj().f("id", e.getKey()).f("amount", e.getValue()).end();
                j.endArr().end();
            }
            j.name("links").arr();
            if (engine != null) {
                Map<String, Double> links = engine.settings().spillovers.get(id.toLowerCase());
                if (links != null) for (var e : links.entrySet()) j.obj().f("id", e.getKey()).f("strength", e.getValue()).end();
            }
            j.endArr();
            j.end();
        })));
    }

    public Payload history(String id, String span) {
        Item item = MarketManager.getInstance().getItem(id);
        if (item == null || !item.isParent()) return null;
        String s = switch (span == null ? "" : span) { case "day", "month", "year", "all" -> span; default -> "hour"; };
        long ttl = s.equals("hour") ? shortTtl() : heavyTtl();
        return cache.get("hist:" + id + ":" + s, ttl, () -> Payload.json(JsonOut.build(j -> {
            j.obj().f("span", s);
            List<long[]> times = new ArrayList<>();
            List<double[]> rows = new ArrayList<>();
            if (s.equals("hour")) {
                List<Double> vals = new ArrayList<>(item.getPrice().getValuesPastHour());
                long now = System.currentTimeMillis();
                for (int i = 0; i < vals.size(); i++) {
                    times.add(new long[] { now - (vals.size() - 1 - i) * 60_000L });
                    rows.add(new double[] { vals.get(i) == null ? 0 : vals.get(i), 0 });
                }
            } else {
                Database db = DatabaseManager.get().getDatabase();
                List<Instant> inst = switch (s) {
                    case "day" -> db.getDayPrices(item);
                    case "month" -> db.getMonthPrices(item);
                    case "year" -> db.getYearPrices(item);
                    default -> db.getAllPrices(item);
                };
                for (Instant in : inst) {
                    if (in.getLocalDateTime() == null || in.getPrice() <= 0) continue;
                    times.add(new long[] { epoch(in.getLocalDateTime()) });
                    rows.add(new double[] { in.getPrice(), in.getVolume() });
                }
            }
            j.name("t").arr();
            for (long[] t : times) j.val(t[0]);
            j.endArr().name("p").arr();
            for (double[] r : rows) j.val(r[0]);
            j.endArr().name("v").arr();
            for (double[] r : rows) j.val(r[1]);
            j.endArr().end();
        })));
    }

    public Payload icon(String id) {
        Item item = MarketManager.getInstance().getItem(id);
        if (item == null || item.getIcon() == null) return null;

        return cache.get("icon:" + id, 86_400_000L, () -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                BufferedImage img = item.getIcon();
                ImageIO.write(img, "png", out);
                return Payload.of("image/png", out.toByteArray());
            } catch (IOException e) {
                return Payload.of("image/png", new byte[0]);
            }
        });
    }

    public Payload recentTrades(int limit) {
        if (!settings.tradesFeed) return null;
        return cache.get("trades:" + limit, shortTtl(), () -> {
            List<Trade> trades = DatabaseManager.get().getDatabase().retrieveTrades(0, limit);
            Map<UUID, String> names = names(trades.stream().map(Trade::getUuid).distinct().toList());
            return Payload.json(JsonOut.build(j -> {
                j.arr();
                for (Trade t : trades) {
                    if (t.getItem() == null) continue;
                    j.obj()
                        .f("t", t.getDate() == null ? 0 : epoch(t.getDate()))
                        .f("item", t.getItem().getIdentifier())
                        .f("name", t.getItem().getName())
                        .f("amount", t.getAmount())
                        .f("value", t.getValue())
                        .f("buy", t.isBuy())
                        .f("player", displayName(t.getUuid(), names.get(t.getUuid())))
                        .end();
                }
                j.endArr();
            }));
        });
    }

    public Payload economy() {
        EconomyEngine engine = EconomyEngine.get();
        if (engine == null) return null;
        return cache.get("economy", shortTtl(), () -> Payload.json(JsonOut.build(j -> {
            MacroSnapshot s = engine.latest();
            EconomySettings cfg = engine.settings();
            j.obj();
            snapshotFields(j, s);
            j.f("treasury", engine.treasury())
             .f("loanRate", EconomyEngine.loanDailyRate());
            j.name("targets").obj()
                .f("inflation", cfg.inflationTarget)
                .f("neutralRate", cfg.neutralRate)
                .f("reserve", cfg.treasuryReserve)
                .f("recessionGap", cfg.recessionGap).end();
            j.name("features").obj()
                .f("centralBank", cfg.cbEnabled).f("rateMode", cfg.cbFixedMode ? "fixed" : "taylor")
                .f("liquidity", cfg.cbEnabled && cfg.liquidityEnabled)
                .f("priceLevel", cfg.priceLevelEnabled).f("treasury", cfg.treasuryEnabled)
                .f("stabilizers", cfg.treasuryEnabled && cfg.stabilizersEnabled)
                .f("ubi", cfg.treasuryEnabled && cfg.ubiEnabled)
                .f("wealthTax", cfg.treasuryEnabled && cfg.wealthTaxEnabled)
                .f("meanReversion", cfg.meanReversionEnabled).f("spillovers", cfg.spilloverEnabled)
                .f("recipes", cfg.recipesEnabled).f("dynamicSpread", cfg.dynamicSpreadEnabled)
                .f("shocks", cfg.shocksEnabled).end();

            j.name("sectors").arr();
            MarketManager m = MarketManager.getInstance();
            for (var e : s.sectorIndices().entrySet()) {
                Category c = m.getCategoryFromIdentifier(e.getKey());
                j.obj().f("id", e.getKey()).f("name", c == null ? e.getKey() : c.getDisplayName()).f("index", e.getValue()).end();
            }
            j.endArr();

            j.name("shocks").arr();
            for (Shock sh : engine.activeShocks()) {
                Category c = sh.category() == null ? null : m.getCategoryFromIdentifier(sh.category());
                j.obj().f("kind", sh.kind().name().toLowerCase()).f("category", sh.category())
                        .f("categoryName", c == null ? null : c.getDisplayName())
                        .f("impact", Math.exp(sh.logImpact()) - 1).f("start", sh.start()).f("end", sh.end()).end();
            }
            j.endArr();
            j.end();
        })));
    }

    private static void snapshotFields(JsonOut j, MacroSnapshot s) throws IOException {
        j.f("ts", s.timestamp()).f("cpi", s.cpi()).f("inflation", s.inflation())
         .f("gdp", s.gdp()).f("realGdp", s.realGdp()).f("moneySupply", s.moneySupply())
         .f("velocity", s.velocity()).f("outputGap", s.outputGap())
         .f("phase", s.phase().name().toLowerCase()).f("recession", s.recession())
         .f("policyRate", s.policyRate()).f("liquidity", s.liquidity()).f("taxScale", s.taxScale())
         .f("priceLevel", s.priceLevel()).f("ubi", s.ubiPerCapita()).f("gini", s.gini())
         .f("debt", s.outstandingDebt()).f("taxes", s.taxes()).f("moneyCreated", s.moneyCreated())
         .f("moneyDestroyed", s.moneyDestroyed()).f("trades", s.trades());
    }

    public Payload economyHistory(String range) {
        EconomyEngine engine = EconomyEngine.get();
        if (engine == null) return null;
        String r = switch (range == null ? "" : range) { case "1d", "30d", "90d", "all" -> range; default -> "7d"; };
        long since = switch (r) {
            case "1d" -> System.currentTimeMillis() - 86_400_000L;
            case "30d" -> System.currentTimeMillis() - 30 * 86_400_000L;
            case "90d" -> System.currentTimeMillis() - 90 * 86_400_000L;
            case "all" -> 0L;
            default -> System.currentTimeMillis() - 7 * 86_400_000L;
        };
        return cache.get("ecohist:" + r, heavyTtl(), () -> {
            List<MacroSnapshot> rows = engine.history(since, 0);
            return Payload.json(JsonOut.build(j -> {
                j.obj().f("range", r);
                series(j, "t", rows, s -> (double) s.timestamp());
                series(j, "cpi", rows, MacroSnapshot::cpi);
                series(j, "inflation", rows, MacroSnapshot::inflation);
                series(j, "gdp", rows, MacroSnapshot::gdp);
                series(j, "realGdp", rows, MacroSnapshot::realGdp);
                series(j, "moneySupply", rows, MacroSnapshot::moneySupply);
                series(j, "velocity", rows, MacroSnapshot::velocity);
                series(j, "outputGap", rows, MacroSnapshot::outputGap);
                series(j, "policyRate", rows, MacroSnapshot::policyRate);
                series(j, "liquidity", rows, MacroSnapshot::liquidity);
                series(j, "taxScale", rows, MacroSnapshot::taxScale);
                series(j, "priceLevel", rows, MacroSnapshot::priceLevel);
                series(j, "treasury", rows, MacroSnapshot::treasury);
                series(j, "gini", rows, MacroSnapshot::gini);
                series(j, "taxes", rows, MacroSnapshot::taxes);
                series(j, "moneyCreated", rows, MacroSnapshot::moneyCreated);
                series(j, "moneyDestroyed", rows, MacroSnapshot::moneyDestroyed);
                series(j, "trades", rows, s -> (double) s.trades());
                j.name("phase").arr();
                for (MacroSnapshot s : rows) j.val(s.phase().name().toLowerCase());
                j.endArr();
                j.end();
            }));
        });
    }

    private interface Metric { double of(MacroSnapshot s); }

    private static void series(JsonOut j, String name, List<MacroSnapshot> rows, Metric m) throws IOException {
        j.name(name).arr();
        for (MacroSnapshot s : rows) j.val(m.of(s));
        j.endArr();
    }

    private BaseDatabase sql() {
        Database d = DatabaseManager.get().getDatabase();
        return d instanceof BaseDatabase b ? b : null;
    }

    public Payload analytics() {
        BaseDatabase db = sql();
        if (db == null) return null;
        return cache.get("analytics", heavyTtl(), () -> {
            EconomyEngine engine = EconomyEngine.get();
            int days = engine == null ? 7 : engine.settings().analyticsWindowDays;
            int lookback = engine == null ? 30 : engine.settings().giniLookbackDays;
            int since = NormalisedDate.getDays() - days;

            record Data(Map<String, Double> wealth, List<EconomyData.TraderRow> traders, double[] volumes,
                        List<EconomyData.ItemFlow> flows, double[][] hours) {}
            Data d = db.queryConnection(c -> new Data(
                    settings.showWealth ? EconomyData.wealthByPlayer(c, NormalisedDate.getDays() - lookback) : Map.of(),
                    EconomyData.topTraders(c, since, 10),
                    EconomyData.traderVolumes(c, since),
                    EconomyData.itemFlows(c, since),
                    EconomyData.hourlyActivity(c, since)));

            return Payload.json(JsonOut.build(j -> {
                j.obj().f("windowDays", days);

                if (settings.showWealth) {
                    double[] w = d.wealth().values().stream().mapToDouble(Double::doubleValue).toArray();
                    j.name("wealth").obj()
                        .f("holders", w.length)
                        .f("gini", EconomyMath.gini(w))
                        .f("top1", EconomyMath.topShare(w, 0.01))
                        .f("top10", EconomyMath.topShare(w, 0.10))
                        .f("bottom50", 1 - EconomyMath.topShare(w, 0.50));
                    j.name("lorenz").arr();
                    for (double v : EconomyMath.lorenz(w, 20)) j.val(v);
                    j.endArr();
                    histogram(j, w);
                    j.end();
                }

                j.f("hhi", EconomyMath.hhi(d.volumes())).f("traders", d.volumes().length);

                j.name("topTraders").arr();
                for (EconomyData.TraderRow t : d.traders()) {
                    UUID uuid = parseUuid(t.uuid());
                    j.obj().f("player", displayName(uuid, t.name())).f("volume", t.volume()).f("trades", t.trades()).end();
                }
                j.endArr();

                MarketManager m = MarketManager.getInstance();
                List<EconomyData.ItemFlow> flows = new ArrayList<>(d.flows());
                flows.sort(Comparator.comparingDouble((EconomyData.ItemFlow f) -> f.bought() + f.sold()).reversed());
                j.name("items").arr();
                Map<String, double[]> byCategory = new LinkedHashMap<>();
                for (EconomyData.ItemFlow f : flows) {
                    Item item = m.getItem(f.identifier());
                    String cat = item != null && item.getCategory() != null ? item.getCategory().getIdentifier() : null;
                    if (cat != null) {
                        double[] acc = byCategory.computeIfAbsent(cat, k -> new double[2]);
                        acc[0] += f.bought();
                        acc[1] += f.sold();
                    }
                }
                for (int i = 0; i < Math.min(15, flows.size()); i++) {
                    EconomyData.ItemFlow f = flows.get(i);
                    Item item = m.getItem(f.identifier());
                    j.obj().f("id", f.identifier()).f("name", item == null ? f.identifier() : item.getName())
                            .f("bought", f.bought()).f("sold", f.sold())
                            .f("unitsBought", f.unitsBought()).f("unitsSold", f.unitsSold()).f("trades", f.trades()).end();
                }
                j.endArr();

                j.name("categories").arr();
                for (var e : byCategory.entrySet()) {
                    Category c = m.getCategoryFromIdentifier(e.getKey());
                    j.obj().f("id", e.getKey()).f("name", c == null ? e.getKey() : c.getDisplayName())
                            .f("bought", e.getValue()[0]).f("sold", e.getValue()[1]).end();
                }
                j.endArr();

                j.name("hourly").arr();
                for (double[] h : d.hours()) j.arr().val(h[0]).val(h[1]).endArr();
                j.endArr();

                List<Item> parents = new ArrayList<>(m.getAllParentItems());
                Map<Item, Double> vol = new HashMap<>();
                for (Item it : parents) {
                    List<Double> hv = it.getPrice().getValuesPastHour();
                    vol.put(it, hv == null ? 0 : EconomyMath.logReturnVolatility(new ArrayList<>(hv)) * Math.sqrt(60));
                }
                parents.sort(Comparator.comparingDouble((Item it) -> vol.get(it)).reversed());
                j.name("volatile").arr();
                for (int i = 0; i < Math.min(10, parents.size()); i++) {
                    Item it = parents.get(i);
                    j.obj().f("id", it.getIdentifier()).f("name", it.getName()).f("volatility", vol.get(it)).end();
                }
                j.endArr();
                j.end();
            }));
        });
    }

    private static void histogram(JsonOut j, double[] wealth) throws IOException {
        int[] buckets = new int[10];
        for (double w : wealth) {
            int b = w < 10 ? 0 : Math.min(9, (int) Math.floor(Math.log10(w)));
            buckets[b]++;
        }
        j.name("histogram").arr();
        for (int i = 0; i < buckets.length; i++) j.obj().f("from", i == 0 ? 0 : Math.pow(10, i)).f("count", buckets[i]).end();
        j.endArr();
    }

    public Payload leaderboard() {
        BaseDatabase db = sql();
        if (db == null) return null;
        return cache.get("leaderboard", heavyTtl(), () -> {
            Map<String, Double> wealth = db.queryConnection(c -> EconomyData.wealthByPlayer(c, NormalisedDate.getDays() - 30));
            List<Map.Entry<String, Double>> top = new ArrayList<>(wealth.entrySet());
            top.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            List<Map.Entry<String, Double>> slice = top.subList(0, Math.min(25, top.size()));
            List<UUID> ids = slice.stream().map(e -> parseUuid(e.getKey())).filter(u -> u != null).toList();
            Map<UUID, String> names = names(ids);
            return Payload.json(JsonOut.build(j -> {
                j.arr();
                for (var e : slice) {
                    UUID u = parseUuid(e.getKey());
                    j.obj().f("player", displayName(u, u == null ? null : names.get(u))).f("wealth", e.getValue()).end();
                }
                j.endArr();
            }));
        });
    }

    private Map<UUID, String> names(List<UUID> uuids) {
        Map<UUID, String> out = new HashMap<>();
        if (!settings.showNames) return out;
        Database db = DatabaseManager.get().getDatabase();
        for (UUID u : uuids) {
            if (u == null) continue;
            try { out.put(u, db.getNameByUUID(u)); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    private String displayName(UUID uuid, String name) {
        if (settings.showNames && name != null && !name.isEmpty()) return name;
        String key = uuid == null ? "?" : uuid.toString();
        return "Trader #" + Auth.sha256(key).substring(0, 4);
    }

    private static UUID parseUuid(String s) {
        try { return UUID.fromString(s); } catch (RuntimeException e) { return null; }
    }

    static <T> T onMain(Nascraft plugin, Supplier<T> task) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        FoliaScheduler.runGlobal(plugin, () -> {
            try { f.complete(task.get()); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f.get(5, TimeUnit.SECONDS);
    }

    public byte[] me(Auth.Session session) throws Exception {
        UUID uuid = session.uuid;
        Currency cur = currency();
        record Holding(String id, String name, int amount, double value) {}
        record Snap(double balance, int capacity, List<Holding> holdings, double debt, boolean online) {}

        Snap snap = onMain(plugin, () -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            Portfolio pf = PortfoliosManager.getInstance().getPortfolio(uuid);
            List<Holding> hs = new ArrayList<>();
            for (var e : pf.getContent().entrySet()) {
                Item it = e.getKey();
                hs.add(new Holding(it.getIdentifier(), it.getName(), e.getValue(), it.sellPrice(e.getValue())));
            }
            return new Snap(MoneyManager.getInstance().getBalance(op, cur), pf.getCapacity(), hs,
                    DebtManager.getInstance().getDebtOfPlayer(uuid), op.isOnline());
        });

        List<Trade> trades = DatabaseManager.get().getDatabase().retrieveTrades(uuid, 0, 15);

        return JsonOut.build(j -> {
            j.obj().f("signedIn", true).f("uuid", uuid.toString()).f("name", session.name).f("online", snap.online())
             .f("balance", snap.balance()).f("debt", snap.debt()).f("loanRate", EconomyEngine.loanDailyRate());
            double total = 0;
            j.name("portfolio").obj().f("capacity", snap.capacity()).name("items").arr();
            for (Holding h : snap.holdings()) {
                total += h.value();
                j.obj().f("id", h.id()).f("name", h.name()).f("amount", h.amount()).f("value", h.value()).end();
            }
            j.endArr().f("value", total).end();
            j.f("netWorth", snap.balance() + total - snap.debt());
            j.name("trades").arr();
            for (Trade t : trades) {
                if (t.getItem() == null) continue;
                j.obj().f("t", t.getDate() == null ? 0 : epoch(t.getDate())).f("item", t.getItem().getIdentifier())
                        .f("name", t.getItem().getName()).f("amount", t.getAmount()).f("value", t.getValue())
                        .f("buy", t.isBuy()).end();
            }
            j.endArr().end();
        });
    }
}
