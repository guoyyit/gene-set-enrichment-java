package com.github.guoyyit.genesetenrichmentjava.tests.stats;

import com.github.guoyyit.genesetenrichmentjava.core.stats.Hypergeometric;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypergeometricTest {

    private static final double EPS = 1e-12;

    @Test
    void upperTailMatchesHandComputedValue() {
        // N=20, K=5, n=4: P(X>=2) = 1205/4845
        assertEquals(1205.0 / 4845.0, Hypergeometric.upperTail(20, 5, 4, 2), EPS);
    }

    @Test
    void lowerTailIsComplementForContinuousBoundary() {
        // P(X<=1) = 1 - P(X>=2)
        assertEquals(1 - 1205.0 / 4845.0, Hypergeometric.lowerTail(20, 5, 4, 1), EPS);
    }

    @Test
    void fullRangeIsOne() {
        assertEquals(1.0, Hypergeometric.upperTail(20, 5, 4, 0), 1e-10);
        assertEquals(1.0, Hypergeometric.lowerTail(20, 5, 4, 4), 1e-10);
    }

    @Test
    void impossibleTailIsZero() {
        // More successes requested than exist.
        assertEquals(0.0, Hypergeometric.upperTail(20, 5, 4, 6), 0.0);
    }

    @Test
    void symmetricDistribution() {
        // Fair split: probability is symmetric around half.
        double pUpper = Hypergeometric.upperTail(10, 5, 4, 3);
        double pLower = Hypergeometric.lowerTail(10, 5, 4, 1);
        assertEquals(pLower, pUpper, EPS);
    }

    @Test
    void staysBoundedForLargePopulation() {
        double p = Hypergeometric.upperTail(2_000_000, 50_000, 30_000, 900);
        assertTrue(p >= 0.0 && p <= 1.0, "p-value out of range: " + p);
    }

    @Test
    void extremeObservationIsStronglySignificant() {
        double p = Hypergeometric.upperTail(100_000, 100, 10_000, 80);
        assertTrue(p < 1e-50, "expected tiny tail, got " + p);
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> Hypergeometric.upperTail(10, 20, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> Hypergeometric.upperTail(10, 5, 30, 1));
    }
}
