package me.bounser.nascraft.exchange.shares;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ShareRegistry {

    private static ShareRegistry instance;

    // playerUuid -> (companyId -> shares)
    private final Map<UUID, Map<UUID, BigDecimal>> holdings = new ConcurrentHashMap<>();

    private ShareRegistry() {}

    public static ShareRegistry getInstance() {
        if (instance == null) {
            instance = new ShareRegistry();
        }
        return instance;
    }

    public BigDecimal getHolding(UUID playerUuid, UUID companyId) {
        return holdings.getOrDefault(playerUuid, Collections.emptyMap())
                .getOrDefault(companyId, BigDecimal.ZERO);
    }

    public Map<UUID, BigDecimal> getShareholders(UUID companyId) {
        Map<UUID, BigDecimal> shareholders = new HashMap<>();
        holdings.forEach((playerUuid, playerHoldings) -> {
            BigDecimal amount = playerHoldings.get(companyId);
            if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                shareholders.put(playerUuid, amount);
            }
        });
        return shareholders;
    }

    public void addShares(UUID playerUuid, UUID companyId, BigDecimal amount) {
        holdings.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                .merge(companyId, amount, BigDecimal::add);
    }

    public void removeShares(UUID playerUuid, UUID companyId, BigDecimal amount) throws InsufficientSharesException {
        Map<UUID, BigDecimal> playerHoldings = holdings.get(playerUuid);
        if (playerHoldings == null || playerHoldings.getOrDefault(companyId, BigDecimal.ZERO).compareTo(amount) < 0) {
            throw new InsufficientSharesException("Player does not have enough shares of company " + companyId);
        }
        playerHoldings.merge(companyId, amount, BigDecimal::subtract);
    }

    public BigDecimal getTotalOutstanding(UUID companyId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<UUID, BigDecimal> playerHoldings : holdings.values()) {
            BigDecimal amount = playerHoldings.get(companyId);
            if (amount != null) {
                total = total.add(amount);
            }
        }
        return total;
    }
}
