package me.bounser.nascraft.exchange;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks currency and shares held on behalf of open limit orders.
 *
 * BUY  limit orders: funds are withdrawn from the player on submission and held here.
 * SELL limit orders: shares are removed from ShareRegistry on submission and held here.
 *
 * On fill: the relevant portion is released to the counterparty.
 * On cancel: the remainder is returned to the owner.
 */
public class EscrowManager {

    private static final EscrowManager INSTANCE = new EscrowManager();
    public static EscrowManager getInstance() { return INSTANCE; }
    private EscrowManager() {}

    // orderId → held currency (BUY limit orders)
    private final Map<UUID, BigDecimal> heldFunds  = new ConcurrentHashMap<>();

    // orderId → held shares + company context (SELL limit orders)
    private final Map<UUID, HeldShares> heldShares = new ConcurrentHashMap<>();

    // ── Funds ─────────────────────────────────────────────────────────────────

    public void holdFunds(UUID orderId, BigDecimal amount) {
        heldFunds.put(orderId, amount);
    }

    /**
     * Consume up to {@code amount} from the escrow for this order.
     * Returns the amount actually consumed (may be less if escrow is partially depleted).
     */
    public BigDecimal consumeFunds(UUID orderId, BigDecimal amount) {
        BigDecimal held = heldFunds.getOrDefault(orderId, BigDecimal.ZERO);
        BigDecimal consume = amount.min(held);
        BigDecimal remaining = held.subtract(consume);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            heldFunds.remove(orderId);
        } else {
            heldFunds.put(orderId, remaining);
        }
        return consume;
    }

    /** Return and clear all remaining held funds for this order. */
    public BigDecimal returnFunds(UUID orderId) {
        BigDecimal v = heldFunds.remove(orderId);
        return v != null ? v : BigDecimal.ZERO;
    }

    public BigDecimal heldFunds(UUID orderId) {
        return heldFunds.getOrDefault(orderId, BigDecimal.ZERO);
    }

    // ── Shares ────────────────────────────────────────────────────────────────

    public void holdShares(UUID orderId, UUID companyId, BigDecimal qty) {
        heldShares.put(orderId, new HeldShares(companyId, qty));
    }

    /**
     * Consume up to {@code qty} shares from the escrow for this order.
     * Returns the amount actually consumed.
     */
    public BigDecimal consumeShares(UUID orderId, BigDecimal qty) {
        HeldShares held = heldShares.get(orderId);
        if (held == null) return BigDecimal.ZERO;
        BigDecimal consume = qty.min(held.qty);
        BigDecimal remaining = held.qty.subtract(consume);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            heldShares.remove(orderId);
        } else {
            heldShares.put(orderId, new HeldShares(held.companyId, remaining));
        }
        return consume;
    }

    /** Return and clear all remaining held shares for this order. Returns null if none held. */
    public HeldShares returnShares(UUID orderId) {
        return heldShares.remove(orderId);
    }

    // ── Value type ────────────────────────────────────────────────────────────

    public static class HeldShares {
        public final UUID companyId;
        public final BigDecimal qty;
        public HeldShares(UUID companyId, BigDecimal qty) {
            this.companyId = companyId;
            this.qty = qty;
        }
    }
}
