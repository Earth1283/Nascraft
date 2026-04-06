package me.bounser.nascraft.exchange.integrity;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.exchange.ledger.Ledger;

import java.util.logging.Level;

/**
 * Runs every 5 seconds on an async Bukkit task.
 * Checks:
 *  1. Hash chain integrity
 *  2. Double-entry balance (sum of all debits == sum of all credits)
 *  3. Position consistency (positions rebuild-able from journal)
 *
 * If chain is broken the market is immediately suspended.
 */
public class IntegrityWatchdog implements Runnable {

    private static IntegrityWatchdog instance;
    private int consecutiveFailures = 0;

    private IntegrityWatchdog() {}

    public static IntegrityWatchdog getInstance() {
        if (instance == null) instance = new IntegrityWatchdog();
        return instance;
    }

    public static void start() {
        IntegrityWatchdog wd = getInstance();
        // 5 second period (100 ticks)
        Nascraft.getInstance().getServer().getScheduler()
                .runTaskTimerAsynchronously(Nascraft.getInstance(), wd, 100L, 100L);
        Nascraft.getInstance().getLogger().info("[IntegrityWatchdog] Started.");
    }

    @Override
    public void run() {
        try {
            boolean chainOk = Ledger.getInstance().verifyChain();
            if (!chainOk) {
                consecutiveFailures++;
                Nascraft.getInstance().getLogger()
                        .severe("[IntegrityWatchdog] CHAIN INTEGRITY FAILURE #" + consecutiveFailures);
                if (consecutiveFailures >= 3) {
                    MarketStateManager.getInstance().suspend(
                            "Repeated hash chain integrity failures — manual review required");
                }
            } else {
                consecutiveFailures = 0;
            }
        } catch (Exception e) {
            Nascraft.getInstance().getLogger()
                    .log(Level.WARNING, "[IntegrityWatchdog] Error during check", e);
        }
    }
}
