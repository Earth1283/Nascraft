package me.bounser.nascraft.exchange.clob;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Per-ticker Central Limit Order Book.
 *
 * Bids: TreeMap descending  (highest bid first)
 * Asks: TreeMap ascending   (lowest ask first)
 * Within the same price level: FIFO (LinkedList preserves insertion order).
 */
public class OrderBook {

    private final String tickerSymbol;

    // price → FIFO queue of orders at that price
    private final TreeMap<BigDecimal, LinkedList<ExchangeOrder>> bids =
            new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<BigDecimal, LinkedList<ExchangeOrder>> asks =
            new TreeMap<>();

    // orderId → order, for fast cancellation
    private final Map<UUID, ExchangeOrder> index = new HashMap<>();

    public OrderBook(String tickerSymbol) {
        this.tickerSymbol = tickerSymbol;
    }

    public String getTickerSymbol() { return tickerSymbol; }

    // ── Mutations ────────────────────────────────────────────────────────────

    public void addOrder(ExchangeOrder order) {
        if (order.getType() == OrderType.MARKET) {
            // Market orders are never resting — they should be matched immediately.
            // If we add them here it's a programming error; guard defensively.
            throw new IllegalArgumentException("Market orders must not be added to the book");
        }
        TreeMap<BigDecimal, LinkedList<ExchangeOrder>> side =
                order.getSide() == OrderSide.BUY ? bids : asks;
        side.computeIfAbsent(order.getPrice(), p -> new LinkedList<>()).addLast(order);
        index.put(order.getOrderId(), order);
    }

    /**
     * Remove a resting order (e.g. after cancel or full fill).
     */
    public void removeOrder(ExchangeOrder order) {
        TreeMap<BigDecimal, LinkedList<ExchangeOrder>> side =
                order.getSide() == OrderSide.BUY ? bids : asks;
        LinkedList<ExchangeOrder> level = side.get(order.getPrice());
        if (level != null) {
            level.remove(order);
            if (level.isEmpty()) side.remove(order.getPrice());
        }
        index.remove(order.getOrderId());
    }

    public boolean cancelOrder(UUID orderId) {
        ExchangeOrder order = index.get(orderId);
        if (order == null) return false;
        order.cancel();
        removeOrder(order);
        return true;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public Optional<BigDecimal> getBestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstKey());
    }

    public Optional<BigDecimal> getBestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstKey());
    }

    public Optional<BigDecimal> getSpread() {
        if (bids.isEmpty() || asks.isEmpty()) return Optional.empty();
        return Optional.of(asks.firstKey().subtract(bids.firstKey()));
    }

    /** Returns the FIFO queue at the given price level (may be empty list). */
    public LinkedList<ExchangeOrder> getLevelQueue(BigDecimal price, OrderSide side) {
        TreeMap<BigDecimal, LinkedList<ExchangeOrder>> book = side == OrderSide.BUY ? bids : asks;
        return book.getOrDefault(price, new LinkedList<>());
    }

    /** Best-price-first iterator over resting bids. */
    public List<ExchangeOrder> getAllBids() {
        return bids.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    /** Best-price-first iterator over resting asks. */
    public List<ExchangeOrder> getAllAsks() {
        return asks.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    public Optional<ExchangeOrder> getOrderById(UUID orderId) {
        return Optional.ofNullable(index.get(orderId));
    }

    /** Peek at the best bid order (without removing). */
    public Optional<ExchangeOrder> peekBestBid() {
        if (bids.isEmpty()) return Optional.empty();
        LinkedList<ExchangeOrder> lvl = bids.firstEntry().getValue();
        return lvl.isEmpty() ? Optional.empty() : Optional.of(lvl.getFirst());
    }

    /** Peek at the best ask order (without removing). */
    public Optional<ExchangeOrder> peekBestAsk() {
        if (asks.isEmpty()) return Optional.empty();
        LinkedList<ExchangeOrder> lvl = asks.firstEntry().getValue();
        return lvl.isEmpty() ? Optional.empty() : Optional.of(lvl.getFirst());
    }

    TreeMap<BigDecimal, LinkedList<ExchangeOrder>> getBids() { return bids; }
    TreeMap<BigDecimal, LinkedList<ExchangeOrder>> getAsks() { return asks; }
}
