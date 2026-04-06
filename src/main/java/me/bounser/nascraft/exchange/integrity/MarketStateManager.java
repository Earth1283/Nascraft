package me.bounser.nascraft.exchange.integrity;

import me.bounser.nascraft.Nascraft;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Thread-safe market state machine.
 * trip() is synchronous and never defers to a scheduler, ensuring
 * that halts take effect within the same call stack as the trigger.
 */
public class MarketStateManager {

    private static MarketStateManager instance;

    private final AtomicReference<MarketState> state = new AtomicReference<>(MarketState.OPEN);

    // Optional incident report required to resume from SUSPENDED.
    private volatile String incidentReport = null;

    private MarketStateManager() {}

    public static MarketStateManager getInstance() {
        if (instance == null) instance = new MarketStateManager();
        return instance;
    }

    public MarketState getState() { return state.get(); }

    public boolean isOpen() { return state.get() == MarketState.OPEN; }

    /**
     * Synchronously trip the market to HALTED.
     * Called from circuit breaker logic; never deferred.
     */
    public void trip(String reason) {
        MarketState prev = state.getAndSet(MarketState.HALTED);
        if (prev != MarketState.HALTED) {
            Nascraft.getInstance().getLogger().warning("[Exchange] Market HALTED: " + reason);
        }
    }

    /**
     * Manually suspend the market (requires an incident report to resume).
     */
    public void suspend(String reason) {
        state.set(MarketState.SUSPENDED);
        incidentReport = null;
        Nascraft.getInstance().getLogger().warning("[Exchange] Market SUSPENDED: " + reason);
    }

    public void close() {
        state.set(MarketState.CLOSED);
        Nascraft.getInstance().getLogger().info("[Exchange] Market CLOSED.");
    }

    /**
     * Resume from HALTED. Allowed without an incident report.
     */
    public boolean resume() {
        if (state.get() == MarketState.HALTED) {
            state.set(MarketState.OPEN);
            Nascraft.getInstance().getLogger().info("[Exchange] Market RESUMED (was HALTED).");
            return true;
        }
        return false;
    }

    /**
     * Resume from SUSPENDED. Requires an incident report filed first via
     * {@link #fileIncidentReport(String)}.
     */
    public boolean resumeFromSuspension() {
        if (state.get() != MarketState.SUSPENDED) return false;
        if (incidentReport == null || incidentReport.isBlank()) {
            Nascraft.getInstance().getLogger()
                    .warning("[Exchange] Cannot resume: incident report not filed.");
            return false;
        }
        state.set(MarketState.OPEN);
        Nascraft.getInstance().getLogger()
                .info("[Exchange] Market RESUMED from SUSPENDED. Report: " + incidentReport);
        incidentReport = null;
        return true;
    }

    public void fileIncidentReport(String report) {
        this.incidentReport = report;
        Nascraft.getInstance().getLogger()
                .info("[Exchange] Incident report filed: " + report);
    }

    public String getIncidentReport() { return incidentReport; }
}
