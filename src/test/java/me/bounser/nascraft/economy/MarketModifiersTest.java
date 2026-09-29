package me.bounser.nascraft.economy;

import me.bounser.nascraft.market.support.MarketTestFixture;
import me.bounser.nascraft.market.unit.Price;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketModifiersTest extends MarketTestFixture {

    @AfterEach
    void resetModifiers() { MarketModifiers.reset(); }

    @Test
    @DisplayName("Neutral knobs return the configured multipliers untouched")
    void neutralIsIdentity() {
        MarketModifiers.reset();
        assertEquals(1.1f, MarketModifiers.effectiveBuy(1.1f, 0.9f, 0));
        assertEquals(0.9f, MarketModifiers.effectiveSell(1.1f, 0.9f, 0));
    }

    @Test
    @DisplayName("Tax scale shrinks or widens only the tax part of the spread")
    void taxScale() {
        MarketModifiers.set(1, 0.5, 1, 0.005);
        assertEquals(1.05f, MarketModifiers.effectiveBuy(1.1f, 0.9f, 0), 1e-6);
        assertEquals(0.95f, MarketModifiers.effectiveSell(1.1f, 0.9f, 0), 1e-6);
    }

    @Test
    @DisplayName("EXPLOIT: no mix of levers lets a round trip break even")
    void roundTripGuard() {
        double[][] levers = { { 0, 1.5 }, { 0.5, 1.02 }, { 0, 1.02 }, { 1.5, 0.95 }, { 0.01, 5 } };
        for (double[] l : levers) {
            MarketModifiers.set(1, l[0], l[1], 0.005);
            float buy = MarketModifiers.effectiveBuy(1.04f, 0.94f, 0);
            float sell = MarketModifiers.effectiveSell(1.04f, 0.94f, 0);
            assertTrue(sell <= buy * (1 - 0.005) + 1e-6, "taxScale=" + l[0] + " liquidity=" + l[1] + " → " + buy + "/" + sell);
        }
    }

    @Test
    @DisplayName("EXPLOIT: buy-then-sell loses money on a real price under aggressive easing")
    void roundTripOnPrice() {
        Price price = aPrice().initialValue(100f).elasticity(10f).taxes(1.02f, 0.98f).precision(6).build();
        MarketModifiers.set(1, 0, 1.02, 0.005); // taxes zeroed, payouts boosted

        double spent = price.getProjectedCost(-10, price.getBuyTaxMultiplier());
        price.changeStock(-10);
        double received = price.getProjectedCost(10, price.getSellTaxMultiplier());
        assertTrue(received < spent, "received " + received + " >= spent " + spent);
    }

    @Test
    @DisplayName("Volatility spread widens both sides")
    void extraSpread() {
        MarketModifiers.reset();
        assertTrue(MarketModifiers.effectiveBuy(1.1f, 0.9f, 0.02) > 1.1f);
        assertTrue(MarketModifiers.effectiveSell(1.1f, 0.9f, 0.02) < 0.9f);
    }

    @Test
    @DisplayName("Price level scales values and the cost integral proportionally")
    void priceLevelScalesPrice() {
        Price price = aPrice().initialValue(100f).elasticity(10f).noTaxes().precision(6).build();
        price.setStock(50);
        double v1 = price.getValue();
        double c1 = price.getProjectedCost(-5, 1f);

        MarketModifiers.set(2, 1, 1, 0.005);
        price.updateValue();
        assertEquals(v1 * 2, price.getValue(), v1 * 1e-5);
        assertEquals(c1 * 2, price.getProjectedCost(-5, 1f), c1 * 1e-4);
        // Inverse mapping stays consistent at the new level.
        assertEquals(50, price.getStockFromValue(price.getValue()), 0.01);
    }

    @Test
    void invalidInputsFallBackToNeutral() {
        MarketModifiers.set(Double.NaN, -1, Double.POSITIVE_INFINITY, 0.005);
        assertEquals(1, MarketModifiers.priceLevel());
        assertEquals(1, MarketModifiers.taxScale());
        assertEquals(1, MarketModifiers.liquidity());
    }
}
