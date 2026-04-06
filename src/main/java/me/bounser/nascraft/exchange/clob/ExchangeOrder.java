package me.bounser.nascraft.exchange.clob;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single order in the Central Limit Order Book.
 * BigDecimal is used for all monetary/share quantities (never double).
 */
public class ExchangeOrder {

    private final UUID orderId;
    private final UUID ownerUuid;
    private final String tickerSymbol;
    private final OrderSide side;
    private final OrderType type;

    /** Null for MARKET orders. */
    private final BigDecimal price;

    private final BigDecimal quantity;
    private BigDecimal filledQuantity;
    private OrderStatus status;

    private final long createdAt;
    /** 0 = GTC (good-till-cancelled). */
    private final long expiresAt;
    private long updatedAt;

    public ExchangeOrder(UUID orderId, UUID ownerUuid, String tickerSymbol,
                         OrderSide side, OrderType type, BigDecimal price,
                         BigDecimal quantity, long createdAt, long expiresAt) {
        this.orderId       = orderId;
        this.ownerUuid     = ownerUuid;
        this.tickerSymbol  = tickerSymbol;
        this.side          = side;
        this.type          = type;
        this.price         = price;
        this.quantity      = quantity;
        this.filledQuantity = BigDecimal.ZERO;
        this.status        = OrderStatus.OPEN;
        this.createdAt     = createdAt;
        this.expiresAt     = expiresAt;
        this.updatedAt     = createdAt;
    }

    // ── Convenience factory methods ──────────────────────────────────────────

    public static ExchangeOrder limitBuy(UUID owner, String ticker, BigDecimal price, BigDecimal qty) {
        return new ExchangeOrder(UUID.randomUUID(), owner, ticker, OrderSide.BUY, OrderType.LIMIT,
                price, qty, System.currentTimeMillis(), 0);
    }

    public static ExchangeOrder limitSell(UUID owner, String ticker, BigDecimal price, BigDecimal qty) {
        return new ExchangeOrder(UUID.randomUUID(), owner, ticker, OrderSide.SELL, OrderType.LIMIT,
                price, qty, System.currentTimeMillis(), 0);
    }

    public static ExchangeOrder marketBuy(UUID owner, String ticker, BigDecimal qty) {
        return new ExchangeOrder(UUID.randomUUID(), owner, ticker, OrderSide.BUY, OrderType.MARKET,
                null, qty, System.currentTimeMillis(), 0);
    }

    public static ExchangeOrder marketSell(UUID owner, String ticker, BigDecimal qty) {
        return new ExchangeOrder(UUID.randomUUID(), owner, ticker, OrderSide.SELL, OrderType.MARKET,
                null, qty, System.currentTimeMillis(), 0);
    }

    // ── Mutations (only from MatchingEngine) ─────────────────────────────────

    public void fill(BigDecimal qty) {
        filledQuantity = filledQuantity.add(qty);
        updatedAt = System.currentTimeMillis();
        BigDecimal remaining = quantity.subtract(filledQuantity);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            status = OrderStatus.FILLED;
        } else {
            status = OrderStatus.PARTIALLY_FILLED;
        }
    }

    public void cancel() {
        status = OrderStatus.CANCELLED;
        updatedAt = System.currentTimeMillis();
    }

    public void expire() {
        status = OrderStatus.EXPIRED;
        updatedAt = System.currentTimeMillis();
    }

    public BigDecimal getRemainingQuantity() {
        return quantity.subtract(filledQuantity).max(BigDecimal.ZERO);
    }

    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    public boolean isActive() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getOrderId()        { return orderId; }
    public UUID getOwnerUuid()      { return ownerUuid; }
    public String getTickerSymbol() { return tickerSymbol; }
    public OrderSide getSide()      { return side; }
    public OrderType getType()      { return type; }
    public BigDecimal getPrice()    { return price; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getFilledQuantity() { return filledQuantity; }
    public OrderStatus getStatus()  { return status; }
    public long getCreatedAt()      { return createdAt; }
    public long getExpiresAt()      { return expiresAt; }
    public long getUpdatedAt()      { return updatedAt; }

    @Override
    public String toString() {
        return String.format("ExchangeOrder{%s %s %s %s@%s rem=%s status=%s}",
                side, type, tickerSymbol, quantity, price, getRemainingQuantity(), status);
    }
}
