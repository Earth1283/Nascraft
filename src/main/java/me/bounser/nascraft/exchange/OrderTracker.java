package me.bounser.nascraft.exchange;

import me.bounser.nascraft.exchange.clob.ExchangeOrder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** In-memory registry of every player's open orders. */
public class OrderTracker {

    private static final OrderTracker INSTANCE = new OrderTracker();
    public static OrderTracker getInstance() { return INSTANCE; }
    private OrderTracker() {}

    // playerUuid → (orderId → order)
    private final Map<UUID, Map<UUID, ExchangeOrder>> orders = new ConcurrentHashMap<>();

    public void track(UUID playerUuid, ExchangeOrder order) {
        orders.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
              .put(order.getOrderId(), order);
    }

    public void remove(UUID playerUuid, UUID orderId) {
        Map<UUID, ExchangeOrder> map = orders.get(playerUuid);
        if (map != null) map.remove(orderId);
    }

    public List<ExchangeOrder> getOpenOrders(UUID playerUuid) {
        Map<UUID, ExchangeOrder> map = orders.get(playerUuid);
        if (map == null) return Collections.emptyList();
        return map.values().stream()
                .filter(ExchangeOrder::isActive)
                .collect(Collectors.toList());
    }

    public Optional<ExchangeOrder> findOrder(UUID playerUuid, UUID orderId) {
        Map<UUID, ExchangeOrder> map = orders.get(playerUuid);
        return map == null ? Optional.empty() : Optional.ofNullable(map.get(orderId));
    }

    public int countOpenOrders(UUID playerUuid) {
        return getOpenOrders(playerUuid).size();
    }
}
