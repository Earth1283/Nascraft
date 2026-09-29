package me.bounser.nascraft.economy;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.crossserver.RedisManager;
import me.bounser.nascraft.database.BaseDatabase;
import me.bounser.nascraft.database.Database;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.database.commands.EconomyData;
import me.bounser.nascraft.database.commands.resources.NormalisedDate;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.formatter.Style;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import me.bounser.nascraft.managers.currencies.Currency;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.resources.Category;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.unit.Price;
import me.bounser.nascraft.scheduler.FoliaScheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

/**
 * Runs the server economy: measures it, sets policy, and feeds the results
 * back into prices, loans and players' wallets.
 *
 * <p>Every node records its own trades into a ledger and flushes it to the
 * shared database each tick. The primary node aggregates all nodes' windows,
 * runs {@link PolicyEngine}, persists the new state and runs item dynamics.
 * Followers just load and apply the policy state.
 */
public final class EconomyEngine {

    public static final String TREASURY = "treasury";
    private static final String LAST_WEALTH_TAX = "last_wealth_tax";
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;

    private static volatile EconomyEngine instance;

    public static EconomyEngine get() { return instance; }

    public static boolean active() { return instance != null; }

    private final Nascraft plugin;
    private volatile EconomySettings settings;
    private final MicroDynamics micro;

    private volatile PolicyState state = new PolicyState();
    private volatile MacroSnapshot latest = MacroSnapshot.empty();
    private volatile double treasury = 0;

    // Ledger for this node's current window.
    private final DoubleAdder created = new DoubleAdder();
    private final DoubleAdder destroyed = new DoubleAdder();
    private final DoubleAdder taxes = new DoubleAdder();
    private final LongAdder trades = new LongAdder();

    private volatile List<Item> assetSource;
    private long lastMicro = System.currentTimeMillis();
    private long lastPurge = 0;
    private volatile boolean wealthTaxRunning = false;

    private EconomyEngine(Nascraft plugin, EconomySettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.micro = new MicroDynamics(settings, new SecureRandom());
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public static void start(Nascraft plugin) {
        EconomySettings settings = loadSettings(plugin);
        if (!settings.enabled) {
            MarketModifiers.reset();
            plugin.getLogger().info("Economy engine disabled in economy.yml.");
            return;
        }
        if (!(DatabaseManager.get().getDatabase() instanceof BaseDatabase)) {
            plugin.getLogger().warning("Economy engine needs an SQL database (SQLite/MySQL); it stays off with Redis storage.");
            return;
        }

        EconomyEngine engine = new EconomyEngine(plugin, settings);
        engine.load();
        instance = engine;
        engine.schedule();
        plugin.getLogger().info("Economy engine running (tick " + settings.tickSeconds + "s, micro " + settings.microTickSeconds + "s).");
    }

    private static EconomySettings loadSettings(Nascraft plugin) {
        File file = new File(plugin.getDataFolder(), "economy.yml");
        if (!file.exists()) plugin.saveResource("economy.yml", false);
        return EconomySettings.from(YamlConfiguration.loadConfiguration(file));
    }

    /** Re-reads economy.yml. Scheduling intervals take effect on the next restart. */
    public void reload() {
        settings = loadSettings(plugin);
        micro.setSettings(settings);
        applyModifiers();
    }

    /** Flushes the open ledger window. Called on shutdown. */
    public void shutdown() {
        try { flushWindow(System.currentTimeMillis()); }
        catch (RuntimeException e) { plugin.getLogger().warning("Economy: final ledger flush failed: " + e.getMessage()); }
        instance = null;
    }

    private BaseDatabase db() {
        Database d = DatabaseManager.get().getDatabase();
        return d instanceof BaseDatabase b ? b : null;
    }

    private void load() {
        BaseDatabase db = db();
        db.withConnection(EconomyData::createTables);

        Map<String, Double> kv = db.queryConnection(EconomyData::loadState);
        state = PolicyState.fromMap(kv);
        treasury = kv.getOrDefault(TREASURY, 0.0);
        MacroSnapshot last = db.queryConnection(EconomyData::latestSnapshot);
        if (last != null) latest = last;

        long now = System.currentTimeMillis();
        for (EconomyData.ShockRow r : db.queryConnection(c -> EconomyData.loadShocks(c, now))) {
            try {
                micro.addShock(new Shock(r.id(), Shock.Kind.valueOf(r.kind()), r.category(), r.impact(), r.start(), r.end()));
            } catch (IllegalArgumentException ignored) { }
        }

        refreshAssets();
        applyModifiers();
    }

    private void schedule() {
        long tick = 20L * settings.tickSeconds;
        long microTick = 20L * settings.microTickSeconds;
        FoliaScheduler.runAsyncTimer(plugin, this::safeMacroTick, 20L * 30, tick);
        FoliaScheduler.runAsyncTimer(plugin, this::safeMicroTick, microTick, microTick);
        long ubi = 20L * 60 * settings.ubiIntervalMinutes;
        FoliaScheduler.runAsyncTimer(plugin, this::safeUbi, ubi, ubi);
    }

    // ------------------------------------------------------------------
    // Hooks from the rest of the plugin
    // ------------------------------------------------------------------

    /**
     * Called for every completed market trade.
     * @param worth money that changed hands (what the player paid or received)
     * @param stockChange how far the trade moved the item's stock (0 if limits blocked it)
     */
    public static void recordTrade(Item item, boolean buy, double worth, double stockChange) {
        EconomyEngine e = instance;
        if (e == null || worth <= 0 || !Double.isFinite(worth)) return;
        Currency def = CurrenciesManager.getInstance().getDefaultCurrency();
        if (def != null && item.getCurrency() != null && !def.equals(item.getCurrency())) return;

        Price price = item.getPrice();
        if (buy) {
            e.destroyed.add(worth);
            float mult = price.getBuyTaxMultiplier();
            if (mult > 1) e.taxes.add(worth - worth / mult);
        } else {
            e.created.add(worth);
            float mult = price.getSellTaxMultiplier();
            if (mult > 0) {
                // Only the fiscal part is tax; the central bank's liquidity haircut
                // is money destroyed, not treasury revenue.
                double gross = worth / mult;
                double fiscal = mult / Math.max(1e-9, MarketModifiers.liquidity());
                if (fiscal < 1) e.taxes.add(gross * (1 - fiscal));
            }
        }
        e.trades.increment();
        if (stockChange != 0) e.micro.onTrade(item.isParent() ? item.getIdentifier() : item.getParent().getIdentifier(), stockChange);
    }

    /** Loan interest collected: revenue for the treasury. */
    public static void recordInterest(double amount) {
        EconomyEngine e = instance;
        if (e == null || amount <= 0 || !e.settings.treasuryEnabled) return;
        e.addToTreasuryAsync(amount);
    }

    /** Daily loan rate: the central bank's policy rate if it controls loans, else the configured one. */
    public static double loanDailyRate() {
        double configured = Config.getInstance().getLoansDailyInterest();
        EconomyEngine e = instance;
        if (e == null || !e.settings.cbEnabled || !e.settings.cbControlsLoans) return configured;
        double r = e.state.policyRate;
        return Double.isFinite(r) ? r : configured;
    }

    // ------------------------------------------------------------------
    // Macro tick
    // ------------------------------------------------------------------

    private void safeMacroTick() {
        try { macroTick(); }
        catch (RuntimeException e) { plugin.getLogger().log(Level.WARNING, "Economy tick failed", e); }
    }

    private void macroTick() {
        long now = System.currentTimeMillis();
        flushWindow(now);

        BaseDatabase db = db();
        if (db == null) return;

        if (!Config.getInstance().isPrimaryNode()) {
            // Followers mirror the primary's policy.
            Map<String, Double> kv = db.queryConnection(EconomyData::loadState);
            state = PolicyState.fromMap(kv);
            treasury = kv.getOrDefault(TREASURY, treasury);
            MacroSnapshot last = db.queryConnection(EconomyData::latestSnapshot);
            if (last != null) latest = last;
            applyModifiers();
            return;
        }

        EconomySettings s = settings;
        MarketManager market = MarketManager.getInstance();
        double cpi = market.getConsumerPriceIndex();

        record Measured(EconomyData.Totals day, double[] cpiRef, EconomyData.MoneySupply money,
                        Map<String, Double> wealth, double debt, double treasury) {}

        Measured m = db.queryConnection(c -> new Measured(
                EconomyData.sumWindows(c, now - DAY),
                EconomyData.cpiNear(c, now - (long) (s.inflationReferenceHours * HOUR)),
                EconomyData.moneySupply(c),
                EconomyData.wealthByPlayer(c, NormalisedDate.getDays() - s.giniLookbackDays),
                me.bounser.nascraft.database.commands.Debt.getAllOutstandingDebt(c),
                EconomyData.loadState(c).getOrDefault(TREASURY, 0.0)));

        double[] wealth = m.wealth().values().stream().mapToDouble(Double::doubleValue).toArray();
        double cpiRef = m.cpiRef() != null ? m.cpiRef()[1] : cpi;
        double cpiRefHours = m.cpiRef() != null ? (now - (long) m.cpiRef()[0]) / (double) HOUR : 0;

        PolicyEngine.Inputs in = new PolicyEngine.Inputs(
                now, cpi, cpiRef, cpiRefHours,
                m.day().gdp(), m.money().total(), m.money().holders(), m.treasury(),
                EconomyMath.gini(wealth), m.debt(),
                m.day().taxes(), m.day().created(), m.day().destroyed(), m.day().trades(),
                Bukkit.getOnlinePlayers().size(), sectorIndices());

        PolicyEngine.Result result = PolicyEngine.step(state, in, s);
        state = result.state();
        latest = result.snapshot();
        treasury = m.treasury();

        db.withTransaction(c -> {
            EconomyData.saveState(c, state.toMap());
            EconomyData.insertSnapshot(c, latest);
            if (now - lastPurge > 6 * HOUR) {
                EconomyData.purge(c, now - s.snapshotRetentionDays * DAY);
                lastPurge = now;
            }
        });

        applyModifiers();
        maybeWealthTax(now);
    }

    /** Writes this node's ledger window and credits its taxes to the treasury. */
    private void flushWindow(long now) {
        double cr = created.sumThenReset(), de = destroyed.sumThenReset(), tx = taxes.sumThenReset();
        long tr = trades.sumThenReset();
        if (tr == 0 && cr == 0 && de == 0) return;
        BaseDatabase db = db();
        if (db == null) return;
        String node = Config.getInstance().isCrossServerEnabled() ? Config.getInstance().getNodeId() : "local";
        boolean toTreasury = settings.treasuryEnabled && tx > 0;
        db.withTransaction(c -> {
            EconomyData.insertWindow(c, now, node, cr, de, tx, tr);
            if (toTreasury) EconomyData.addToState(c, TREASURY, tx);
        });
        if (toTreasury) treasury += tx;
    }

    private Map<String, Double> sectorIndices() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Category cat : MarketManager.getInstance().getCategories()) {
            double sum = 0;
            int n = 0;
            for (Item item : MarketManager.getInstance().getAllParentItems()) {
                if (item.getCategory() != cat) continue;
                double init = item.getPrice().getInitialValue();
                if (init <= 0) continue;
                sum += item.getPrice().getValue() / init;
                n++;
            }
            if (n > 0) out.put(cat.getIdentifier(), sum / n * 100);
        }
        return out;
    }

    /** Pushes the policy state into the pricing hot path and re-prices on a level change. */
    private void applyModifiers() {
        EconomySettings s = settings;
        PolicyState st = state;
        double before = MarketModifiers.priceLevel();
        MarketModifiers.set(
                s.priceLevelEnabled ? st.priceLevel : 1,
                s.treasuryEnabled && s.stabilizersEnabled ? st.taxScale : 1,
                s.cbEnabled && s.liquidityEnabled ? st.liquidity : 1,
                s.minSpread);
        if (MarketModifiers.priceLevel() != before)
            for (Item item : MarketManager.getInstance().getAllParentItems()) item.getPrice().updateValue();
    }

    // ------------------------------------------------------------------
    // Micro tick (primary only)
    // ------------------------------------------------------------------

    private void safeMicroTick() {
        try { microTick(); }
        catch (RuntimeException e) { plugin.getLogger().log(Level.WARNING, "Economy micro tick failed", e); }
    }

    private void microTick() {
        long now = System.currentTimeMillis();
        long from = lastMicro;
        lastMicro = now;
        if (!Config.getInstance().isPrimaryNode()) return;

        refreshAssets();

        Shock shock = micro.maybeStartShock(now, (now - from) / (double) HOUR);
        if (shock != null) onShockStarted(shock);

        int touched = micro.tick(from, now);

        RedisManager redis = plugin.getRedisManager();
        if (touched > 0 && redis != null && redis.isConnected())
            redis.advanceAndPublish(MarketManager.getInstance().getAllParentItems());
    }

    private void refreshAssets() {
        List<Item> parents = MarketManager.getInstance().getAllParentItems();
        if (parents == assetSource) return;
        List<MicroDynamics.Asset> assets = new ArrayList<>(parents.size());
        for (Item item : parents)
            assets.add(new MicroDynamics.Asset(item.getIdentifier(),
                    item.getCategory() == null ? null : item.getCategory().getIdentifier(), item.getPrice()));
        micro.setAssets(assets);
        assetSource = parents;
    }

    /** Starts a shock by hand (admin command). */
    public Shock triggerShock(String category, double percent, double hours) {
        long now = System.currentTimeMillis();
        double log = Math.log(1 + percent / 100.0);
        Shock shock = new Shock("adm" + Long.toString(now, 36), Shock.Kind.of(category == null, log),
                category, log, now, now + (long) (hours * HOUR));
        micro.addShock(shock);
        onShockStarted(shock);
        return shock;
    }

    private void onShockStarted(Shock shock) {
        BaseDatabase db = db();
        if (db != null) FoliaScheduler.runAsync(plugin, () -> db.withConnection(c -> EconomyData.saveShock(c,
                new EconomyData.ShockRow(shock.id(), shock.kind().name(), shock.category(), shock.logImpact(), shock.start(), shock.end()))));

        if (!settings.announceShocks) return;
        Message msg = switch (shock.kind()) {
            case SHORTAGE -> Message.ECONOMY_SHOCK_SHORTAGE;
            case GLUT -> Message.ECONOMY_SHOCK_GLUT;
            case BOOM -> Message.ECONOMY_SHOCK_BOOM;
            case SLUMP -> Message.ECONOMY_SHOCK_SLUMP;
        };
        String category = shock.category();
        if (category != null) {
            Category cat = MarketManager.getInstance().getCategoryFromIdentifier(category);
            if (cat != null) category = cat.getDisplayName();
        }
        String text = Lang.get().message(msg)
                .replace("[CATEGORY]", category == null ? "" : category)
                .replace("[PERCENT]", String.valueOf(Math.round(Math.abs(Math.exp(shock.logImpact()) - 1) * 100)))
                .replace("[HOURS]", String.valueOf(Math.max(1, Math.round((shock.end() - shock.start()) / (double) HOUR))));
        FoliaScheduler.runGlobal(plugin, () -> Bukkit.broadcast(MiniMessage.miniMessage().deserialize(text)));
    }

    // ------------------------------------------------------------------
    // Fiscal transfers
    // ------------------------------------------------------------------

    private void safeUbi() {
        try { payUbi(); }
        catch (RuntimeException e) { plugin.getLogger().log(Level.WARNING, "UBI payout failed", e); }
    }

    private void payUbi() {
        EconomySettings s = settings;
        if (!s.treasuryEnabled || !s.ubiEnabled) return;
        double perCapita = Math.floor(state.ubiPerCapita * 100) / 100;
        if (perCapita <= 0) return;

        List<UUID> recipients = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (s.ubiPermission == null || s.ubiPermission.isEmpty() || p.hasPermission(s.ubiPermission))
                recipients.add(p.getUniqueId());
        if (recipients.size() < s.ubiMinOnline) return;

        double total = perCapita * recipients.size();
        BaseDatabase db = db();
        if (db == null || !db.queryConnection(c -> EconomyData.withdrawFromState(c, TREASURY, total, s.treasuryReserve)))
            return;
        treasury -= total;

        Currency currency = CurrenciesManager.getInstance().getDefaultCurrency();
        String msg = Lang.get().message(Message.ECONOMY_UBI_RECEIVED)
                .replace("[AMOUNT]", Formatter.format(currency, perCapita, Style.ROUND_BASIC));
        FoliaScheduler.runGlobal(plugin, () -> {
            for (UUID uuid : recipients) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                MoneyManager.getInstance().simpleDeposit(op, currency, perCapita);
                Player online = op.getPlayer();
                if (online != null) Lang.get().message(online, msg);
            }
        });
    }

    /** Daily progressive wealth tax, processed in small batches on the global thread. */
    private void maybeWealthTax(long now) {
        EconomySettings s = settings;
        if (!s.treasuryEnabled || !s.wealthTaxEnabled || s.wealthTaxBrackets.isEmpty() || wealthTaxRunning) return;
        BaseDatabase db = db();
        double last = db.queryConnection(EconomyData::loadState).getOrDefault(LAST_WEALTH_TAX, 0.0);
        if (now - last < DAY) return;
        db.withConnection(c -> EconomyData.saveState(c, Map.of(LAST_WEALTH_TAX, (double) now)));

        List<UUID> holders = new ArrayList<>();
        db.withConnection(c -> {
            try (var p = c.prepareStatement("SELECT uuid FROM balances WHERE balance > ?")) {
                p.setDouble(1, s.wealthTaxBrackets.get(0).threshold());
                try (var rs = p.executeQuery()) {
                    while (rs.next()) {
                        try { holders.add(UUID.fromString(rs.getString(1))); } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
        });
        if (holders.isEmpty()) return;

        wealthTaxRunning = true;
        Currency currency = CurrenciesManager.getInstance().getDefaultCurrency();
        Iterator<UUID> it = holders.iterator();
        DoubleAdder collected = new DoubleAdder();
        Runnable[] batch = new Runnable[1];
        batch[0] = () -> {
            for (int i = 0; i < s.wealthTaxBatchSize && it.hasNext(); i++) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(it.next());
                double tax = wealthTaxFor(MoneyManager.getInstance().getBalance(op, currency), s.wealthTaxBrackets);
                if (s.wealthTaxMaxPerPlayer > 0) tax = Math.min(tax, s.wealthTaxMaxPerPlayer);
                tax = Math.floor(tax * 100) / 100;
                if (tax <= 0) continue;
                MoneyManager.getInstance().simpleWithdraw(op, currency, tax);
                collected.add(tax);
                Player online = op.getPlayer();
                if (online != null) Lang.get().message(online, Lang.get().message(Message.ECONOMY_WEALTH_TAX_PAID)
                        .replace("[AMOUNT]", Formatter.format(currency, tax, Style.ROUND_BASIC)));
            }
            if (it.hasNext()) FoliaScheduler.runGlobalLater(plugin, batch[0], 1);
            else {
                double total = collected.sum();
                wealthTaxRunning = false;
                if (total > 0) addToTreasuryAsync(total);
                plugin.getLogger().info("Wealth tax collected " + Formatter.roundToDecimals(total, 2) + " from " + holders.size() + " players.");
            }
        };
        FoliaScheduler.runGlobal(plugin, batch[0]);
    }

    /** Marginal tax: each bracket's rate applies only to the part of the balance inside it. */
    public static double wealthTaxFor(double balance, List<EconomySettings.Bracket> brackets) {
        double tax = 0;
        for (int i = 0; i < brackets.size(); i++) {
            EconomySettings.Bracket b = brackets.get(i);
            if (balance <= b.threshold()) break;
            double upper = i + 1 < brackets.size() ? brackets.get(i + 1).threshold() : Double.POSITIVE_INFINITY;
            tax += (Math.min(balance, upper) - b.threshold()) * b.dailyRate();
        }
        return Math.max(0, tax);
    }

    private void addToTreasuryAsync(double amount) {
        BaseDatabase db = db();
        if (db == null) return;
        treasury += amount;
        FoliaScheduler.runAsync(plugin, () -> db.withConnection(c -> EconomyData.addToState(c, TREASURY, amount)));
    }

    /** Admin adjustment: positive deposits, negative withdraws (never below zero). */
    public boolean adjustTreasury(double delta) {
        BaseDatabase db = db();
        if (db == null) return false;
        boolean ok = delta >= 0
                ? db.queryConnection(c -> { EconomyData.addToState(c, TREASURY, delta); return true; })
                : db.queryConnection(c -> EconomyData.withdrawFromState(c, TREASURY, -delta, 0));
        if (ok) treasury += delta;
        return ok;
    }

    // ------------------------------------------------------------------
    // Read side (commands, placeholders, web)
    // ------------------------------------------------------------------

    public EconomySettings settings() { return settings; }

    public PolicyState state() { return state; }

    public MacroSnapshot latest() { return latest; }

    public double treasury() { return treasury; }

    public List<Shock> activeShocks() { return micro.activeShocks(System.currentTimeMillis()); }

    public List<MacroSnapshot> history(long sinceMs, int maxPoints) {
        BaseDatabase db = db();
        if (db == null) return List.of();
        int cap = maxPoints > 0 ? Math.min(maxPoints, settings.historyMaxPoints) : settings.historyMaxPoints;
        return db.queryConnection(c -> EconomyData.loadSnapshots(c, sinceMs, cap));
    }
}
