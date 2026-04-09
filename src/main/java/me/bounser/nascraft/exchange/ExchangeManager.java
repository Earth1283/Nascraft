package me.bounser.nascraft.exchange;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.exchange.clob.ExchangeOrder;
import me.bounser.nascraft.exchange.clob.MatchingEngine;
import me.bounser.nascraft.exchange.clob.OrderStatus;
import me.bounser.nascraft.exchange.clob.OrderType;
import me.bounser.nascraft.exchange.company.Company;
import me.bounser.nascraft.exchange.company.CompanyManager;
import me.bounser.nascraft.exchange.dividends.DividendEngine;
import me.bounser.nascraft.exchange.integrity.IntegrityWatchdog;
import me.bounser.nascraft.exchange.integrity.MarketStateManager;
import me.bounser.nascraft.exchange.ledger.Ledger;
import me.bounser.nascraft.exchange.regulatory.RegulatoryLog;
import me.bounser.nascraft.exchange.shares.ShareRegistry;
import me.bounser.nascraft.exchange.vault.CompanyVaultManager;
import me.bounser.nascraft.config.Config;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Top-level bootstrap for the entire exchange subsystem.
 * Call {@link #init()} from Nascraft.onEnable() after the main DB is ready.
 */
public class ExchangeManager {

    private static ExchangeManager instance;
    private HikariDataSource exchangePool;

    private ExchangeManager() {}

    public static ExchangeManager getInstance() {
        if (instance == null) instance = new ExchangeManager();
        return instance;
    }

    public void init() {
        try {
            exchangePool = buildPool();
            Connection conn = exchangePool.getConnection();

            // Initialise subsystems in dependency order
            Ledger.init(conn);
            RegulatoryLog.init(conn);
            ExchangeDatabase.init(conn);

            // Load companies from DB into memory
            List<Company> companies = ExchangeDatabase.getInstance().loadAllCompanies();
            companies.forEach(CompanyManager.getInstance()::registerCompany);

            // Restore share positions
            Map<UUID, Map<UUID, BigDecimal>> allPositions = ExchangeDatabase.getInstance().loadAllPositions();
            allPositions.forEach((playerUuid, companyMap) ->
                companyMap.forEach((companyId, qty) ->
                    ShareRegistry.getInstance().loadPosition(playerUuid, companyId, qty)));

            // Restore open order books (companies must be loaded first)
            List<ExchangeOrder> openOrders = ExchangeDatabase.getInstance().loadOpenOrders();
            int restored = 0;
            for (ExchangeOrder order : openOrders) {
                if (order.isExpired()) {
                    order.expire();
                    final ExchangeOrder expiredOrder = order;
                    Bukkit.getScheduler().runTaskAsynchronously(Nascraft.getInstance(), () -> {
                        try { ExchangeDatabase.getInstance().updateOrderStatus(
                                expiredOrder.getOrderId(), OrderStatus.EXPIRED, expiredOrder.getFilledQuantity()); }
                        catch (IllegalStateException ignored) {}
                    });
                    continue;
                }
                if (order.getType() == OrderType.LIMIT) {
                    MatchingEngine.getInstance().getOrCreateBook(order.getTickerSymbol()).addOrder(order);
                }
                OrderTracker.getInstance().track(order.getOwnerUuid(), order);
                restored++;
            }
            Nascraft.getInstance().getLogger().info("[ExchangeManager] Restored " + restored + " open orders.");

            // Start integrity watchdog (every 5 s)
            IntegrityWatchdog.start();

            // Wire fill listener: execute economy/share transfers then write ledger entry
            MatchingEngine.getInstance().setFillListener(fill -> {
                // 1. Execute the actual money + share transfer
                FillExecutor.execute(fill);

                // 2. Append to the immutable audit ledger
                try {
                    Ledger.getInstance().record(
                            "EXCHANGE_FILL",
                            "player:" + fill.getTakerOrder().getOwnerUuid(),
                            "player:" + fill.getMakerOrder().getOwnerUuid(),
                            fill.getNotional(),
                            "ticker=" + fill.getMakerOrder().getTickerSymbol()
                              + " qty=" + fill.getFilledQty(),
                            fill.getTakerOrder().getOwnerUuid());
                } catch (Exception e) {
                    Nascraft.getInstance().getLogger()
                            .log(Level.WARNING, "Fill ledger write error", e);
                }
            });

            // Register vault manager as event listener
            CompanyVaultManager.getInstance(); // triggers listener registration in constructor

            // Schedule weekly dividend + snapshot at Sunday midnight
            scheduleWeeklyTasks();

            // Publish chain head hourly to logger (Discord hook can read this)
            Nascraft.getInstance().getServer().getScheduler()
                    .runTaskTimerAsynchronously(Nascraft.getInstance(), () ->
                            Nascraft.getInstance().getLogger()
                                    .info("[Exchange] Chain head: " + Ledger.getInstance().getChainHead()),
                    72000L, 72000L); // 72000 ticks = 1 hour

            Nascraft.getInstance().getLogger().info("[ExchangeManager] Initialised successfully.");

        } catch (Exception e) {
            Nascraft.getInstance().getLogger()
                    .log(Level.SEVERE, "[ExchangeManager] Failed to initialise exchange", e);
        }
    }

    public void shutdown() {
        if (exchangePool != null && !exchangePool.isClosed()) {
            exchangePool.close();
        }
        MarketStateManager.getInstance().close();
        Nascraft.getInstance().getLogger().info("[ExchangeManager] Shutdown.");
    }

    // ── Connection Pool ────────────────────────────────────────────────────────

    private HikariDataSource buildPool() {
        Config cfg = Config.getInstance();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + cfg.getHost() + ":" + cfg.getPort() + "/" + cfg.getDatabase()
                + "?useSSL=false&allowPublicKeyRetrieval=true");
        hc.setUsername(cfg.getUser());
        hc.setPassword(cfg.getPassword());
        hc.setMaximumPoolSize(5);
        hc.setMinimumIdle(2);
        hc.setConnectionTimeout(5000);
        hc.setPoolName("NascraftExchange");
        return new HikariDataSource(hc);
    }

    // ── Scheduling ─────────────────────────────────────────────────────────────

    private void scheduleWeeklyTasks() {
        // Calculate ticks until next Sunday midnight
        Calendar now = Calendar.getInstance();
        Calendar nextSunday = Calendar.getInstance();
        nextSunday.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        nextSunday.set(Calendar.HOUR_OF_DAY, 0);
        nextSunday.set(Calendar.MINUTE, 0);
        nextSunday.set(Calendar.SECOND, 0);
        nextSunday.set(Calendar.MILLISECOND, 0);
        if (nextSunday.before(now)) nextSunday.add(Calendar.WEEK_OF_YEAR, 1);

        long delayMillis = nextSunday.getTimeInMillis() - now.getTimeInMillis();
        long delayTicks  = delayMillis / 50L; // 1 tick ≈ 50ms
        long weeklyTicks = TimeUnit.DAYS.toMillis(7) / 50L;

        Nascraft.getInstance().getServer().getScheduler()
                .runTaskTimerAsynchronously(Nascraft.getInstance(), () -> {
                    CompanyVaultManager.getInstance().takeWeeklySnapshots();
                    DividendEngine.getInstance().runWeeklyDividends();
                }, delayTicks, weeklyTicks);
    }
}
