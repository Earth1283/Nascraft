package me.bounser.nascraft.exchange.clob;

import java.math.BigDecimal;

/** Represents a single trade execution between a maker and a taker order. */
public class Fill {

    private final ExchangeOrder makerOrder;
    private final ExchangeOrder takerOrder;
    private final BigDecimal filledQty;
    private final BigDecimal price;
    private final long timestamp;

    public Fill(ExchangeOrder makerOrder, ExchangeOrder takerOrder,
                BigDecimal filledQty, BigDecimal price) {
        this.makerOrder = makerOrder;
        this.takerOrder = takerOrder;
        this.filledQty  = filledQty;
        this.price      = price;
        this.timestamp  = System.currentTimeMillis();
    }

    public ExchangeOrder getMakerOrder() { return makerOrder; }
    public ExchangeOrder getTakerOrder() { return takerOrder; }
    public BigDecimal getFilledQty()     { return filledQty; }
    public BigDecimal getPrice()         { return price; }
    public long getTimestamp()           { return timestamp; }

    /** Notional value of this fill. */
    public BigDecimal getNotional() { return price.multiply(filledQty); }

    @Override
    public String toString() {
        return String.format("Fill{ticker=%s, qty=%s, price=%s, maker=%s, taker=%s}",
                makerOrder.getTickerSymbol(), filledQty, price,
                makerOrder.getOrderId(), takerOrder.getOrderId());
    }
}
