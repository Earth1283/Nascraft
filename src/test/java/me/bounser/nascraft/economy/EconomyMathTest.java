package me.bounser.nascraft.economy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EconomyMathTest {
    private static final double EPS = 1e-9;

    @Test
    @DisplayName("Gini is 0 for perfect equality and (n-1)/n when one holder owns everything")
    void gini_extremes() {
        assertEquals(0, EconomyMath.gini(new double[] { 5, 5, 5, 5 }), EPS);
        assertEquals(0.75, EconomyMath.gini(new double[] { 0, 0, 0, 100 }), EPS);
    }

    @Test
    @DisplayName("Gini ignores order, treats negatives as zero, and handles empty input")
    void gini_robustness() {
        assertEquals(EconomyMath.gini(new double[] { 1, 2, 3, 10 }), EconomyMath.gini(new double[] { 10, 3, 1, 2 }), EPS);
        assertEquals(EconomyMath.gini(new double[] { 0, 5 }), EconomyMath.gini(new double[] { -50, 5 }), EPS);
        assertEquals(0, EconomyMath.gini(new double[0]), EPS);
        assertEquals(0, EconomyMath.gini(new double[] { 0, 0 }), EPS);
    }

    @Test
    @DisplayName("Lorenz curve starts at 0, ends at 1, is monotone and below the equality line")
    void lorenz_shape() {
        double[] curve = EconomyMath.lorenz(new double[] { 1, 1, 2, 5, 20 }, 10);
        assertEquals(11, curve.length);
        assertEquals(0, curve[0], EPS);
        assertEquals(1, curve[10], EPS);
        for (int i = 1; i < curve.length; i++) {
            assertTrue(curve[i] >= curve[i - 1] - EPS);
            assertTrue(curve[i] <= (double) i / 10 + EPS);
        }
    }

    @Test
    void topShare_and_hhi() {
        double[] v = { 10, 10, 10, 70 };
        assertEquals(0.7, EconomyMath.topShare(v, 0.25), EPS);
        assertEquals(0.01 * 3 + 0.49, EconomyMath.hhi(v), EPS);
        assertEquals(1, EconomyMath.hhi(new double[] { 42 }), EPS);
        assertEquals(0, EconomyMath.hhi(new double[0]), EPS);
    }

    @Test
    @DisplayName("Taylor rule: on-target inflation and zero gap gives neutral + inflation")
    void taylor() {
        assertEquals(0.005, EconomyMath.taylorRate(0.004, 0.001, 0.001, 0.5, 0, 0.005), EPS);

        assertEquals(0.004 + 0.002 + 0.0005, EconomyMath.taylorRate(0.004, 0.002, 0.001, 0.5, 0, 0.005), EPS);

        assertTrue(EconomyMath.taylorRate(0.004, 0.001, 0.001, 0.5, -0.2, 0.005) < 0.005);
    }

    @Test
    @DisplayName("Daily inflation compounds a reading taken over any window")
    void dailyInflation() {
        assertEquals(0.01, EconomyMath.dailyInflation(100, 101, 24), 1e-12);
        assertEquals(Math.pow(1.01, 2) - 1, EconomyMath.dailyInflation(100, 101, 12), 1e-12);
        assertEquals(0, EconomyMath.dailyInflation(0, 101, 24), EPS);
    }

    @Test
    void emaAlpha_halfLife() {
        double a = EconomyMath.emaAlpha(1, 1);
        assertEquals(0.5, a, EPS);
        double v = 0;
        for (int i = 0; i < 10; i++) v = EconomyMath.ema(v, 100, EconomyMath.emaAlpha(0.1, 1));
        assertEquals(50, v, 1e-9);
        assertEquals(7, EconomyMath.ema(Double.NaN, 7, 0.1), EPS);
    }

    @Test
    void volatility() {
        assertEquals(0, EconomyMath.logReturnVolatility(Collections.nCopies(60, 10.0)), EPS);
        List<Double> zigzag = new ArrayList<>();
        for (int i = 0; i < 60; i++) zigzag.add(i % 2 == 0 ? 100.0 : 110.0);
        assertTrue(EconomyMath.logReturnVolatility(zigzag) > 0.09);
        assertEquals(0, EconomyMath.logReturnVolatility(List.of(1.0, 2.0)), EPS);
    }

    @Test
    void phases() {
        assertEquals(EconomyMath.Phase.EXPANSION, EconomyMath.phase(0.1, 0.01));
        assertEquals(EconomyMath.Phase.SLOWDOWN, EconomyMath.phase(0.1, -0.01));
        assertEquals(EconomyMath.Phase.CONTRACTION, EconomyMath.phase(-0.1, -0.01));
        assertEquals(EconomyMath.Phase.RECOVERY, EconomyMath.phase(-0.1, 0.01));
    }
}
