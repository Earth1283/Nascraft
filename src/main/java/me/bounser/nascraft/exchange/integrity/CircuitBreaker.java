package me.bounser.nascraft.exchange.integrity;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto circuit breakers.
 *
 * Per-ticker:
 *  - 15% single-tick price move  → halt ticker + log
 *  - Wash trading (self-trade)   → halt ticker + regulatory flag
 *
 * Global:
 *  - Volume spike (configurable) → trip entire market
 */
public class CircuitBreaker {

    private static CircuitBreaker instance;

    // ticker → last reference price (used to detect 15% spikes)
    private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    // ticker → flagged for regulatory review
    private final Map<String, String> regulatoryFlags = new ConcurrentHashMap<>();

    private CircuitBreaker() {}

    public static CircuitBreaker getInstance() {
        if (instance == null) instance = new CircuitBreaker();
        return instance;
    }

    /**
     * Called after every fill. Checks for price spike.
     *
     * @param ticker    ticker symbol
     * @param fillPrice the price at which this fill occurred
     * @return true if the circuit was tripped
     */
    public boolean checkPriceSpike(String ticker, BigDecimal fillPrice) {
        BigDecimal prev = lastPrices.put(ticker, fillPrice);
        if (prev == null || prev.compareTo(BigDecimal.ZERO) == 0) return false;

        BigDecimal change = fillPrice.subtract(prev).abs()
                .divide(prev, 6, RoundingMode.HALF_UP);

        if (change.compareTo(BigDecimal.valueOf(Config.getInstance().getExchangePriceSpikeThreshold())) >= 0) {
            String reason = String.format("Price spike on %s: %.2f%% move from %s to %s",
                    ticker, change.multiply(BigDecimal.valueOf(100)), prev, fillPrice);
            MarketStateManager.getInstance().trip(reason);
            flag(ticker, reason);
            Nascraft.getInstance().getLogger().warning("[CircuitBreaker] " + reason);
            return true;
        }
        return false;
    }

    /**
     * Detects wash trading (buyer == seller).
     * @return true if self-trade was detected
     */
    public boolean checkWashTrade(String ticker, UUID buyerUuid, UUID sellerUuid) {
        if (buyerUuid.equals(sellerUuid)) {
            String reason = String.format("Wash trade detected on %s by %s", ticker, buyerUuid);
            MarketStateManager.getInstance().trip(reason);
            flag(ticker, "WASH_TRADE: " + reason);
            Nascraft.getInstance().getLogger().warning("[CircuitBreaker] " + reason);
            return true;
        }
        return false;
    }

    public void setReferencePrice(String ticker, BigDecimal price) {
        lastPrices.put(ticker, price);
    }

    public void flag(String ticker, String reason) {
        regulatoryFlags.put(ticker, reason);
        Nascraft.getInstance().getLogger()
                .warning("[RegulatoryFlag] " + ticker + ": " + reason);
    }

    public Map<String, String> getAllFlags() {
        return Map.copyOf(regulatoryFlags);
    }

    public void clearFlag(String ticker) {
        regulatoryFlags.remove(ticker);
    }
}
