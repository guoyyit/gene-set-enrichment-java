package com.github.guoyyit.genesetenrichmentjava.tests.service;

import com.github.guoyyit.genesetenrichmentjava.service.Hypergeometric;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypergeometricTest {

    private static final double EPS = 1e-12;

    @Test
    void upperTailMatchesExactCombinatorialValues() {
        // Reference values computed with exact integer combinatorics (math.comb).
        assertEquals(2.0 / 9.0,
                Hypergeometric.upperTailPValue(10, 5, 2, 2), EPS);
        assertEquals(1.0,
                Hypergeometric.upperTailPValue(10, 5, 2, 0), EPS);
        assertEquals(0.20678534571723428,
                Hypergeometric.upperTailPValue(20, 7, 5, 3), EPS);
        assertEquals(0.0006716277482650501,
                Hypergeometric.upperTailPValue(100, 10, 10, 5), EPS);
    }

    @Test
    void lowerTailMatchesExactCombinatorialValues() {
        assertEquals(1.0,
                Hypergeometric.lowerTailPValue(10, 5, 2, 2), EPS);
        assertEquals(2.0 / 9.0,
                Hypergeometric.lowerTailPValue(10, 5, 2, 0), EPS);
        assertEquals(0.9692982456140351,
                Hypergeometric.lowerTailPValue(20, 7, 5, 3), EPS);
    }

    @Test
    void handlesLargePopulationWithoutOverflow() {
        // N=50000 would overflow factorial/naive binomial implementations.
        // Tolerance accommodates accumulated Lanczos log-gamma error over ~200 terms.
        assertEquals(0.3233154915842948,
                Hypergeometric.upperTailPValue(50000, 500, 200, 3), 1e-9);
        assertEquals(0.858399843371768,
                Hypergeometric.lowerTailPValue(50000, 500, 200, 3), 1e-9);
    }

    @Test
    void tailsSumToSfPlusCdfConsistency() {
        // sf(k) + cdf(k-1) = 1
        double sf = Hypergeometric.upperTailPValue(1000, 250, 80, 30);
        double cdfBefore = Hypergeometric.lowerTailPValue(1000, 250, 80, 29);
        assertEquals(1.0, sf + cdfBefore, 1e-12);
    }

    @Test
    void probabilitiesAreWithinUnitRange() {
        for (int k = 0; k <= 10; k++) {
            double p = Hypergeometric.upperTailPValue(100, 20, 10, k);
            assertTrue(p >= 0.0 && p <= 1.0);
        }
    }

    @Test
    void foldEnrichment() {
        assertEquals(2.0, Hypergeometric.foldEnrichment(10, 5, 2, 2), EPS);
        assertEquals(0.0, Hypergeometric.foldEnrichment(10, 5, 2, 0), EPS);
        assertEquals(1.0, Hypergeometric.foldEnrichment(100, 10, 10, 1), EPS);
    }

    @Test
    void isDeterministic() {
        double first = Hypergeometric.upperTailPValue(50000, 500, 200, 3);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, Hypergeometric.upperTailPValue(50000, 500, 200, 3), 0.0);
        }
    }
}
