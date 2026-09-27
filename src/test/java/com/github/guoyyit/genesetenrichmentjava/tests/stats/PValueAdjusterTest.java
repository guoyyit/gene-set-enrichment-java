package com.github.guoyyit.genesetenrichmentjava.tests.stats;

import com.github.guoyyit.genesetenrichmentjava.core.model.CorrectionMethod;
import com.github.guoyyit.genesetenrichmentjava.core.stats.PValueAdjuster;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PValueAdjusterTest {

    private static final double EPS = 1e-14;

    // R: p.adjust(c(.01,.04,.03,.002,.05), ...)
    private final double[] p = {0.01, 0.04, 0.03, 0.002, 0.05};

    @Test
    void bonferroniMatchesR() {
        double[] expected = {0.05, 0.20, 0.15, 0.01, 0.25};
        assertArrayEquals(expected, PValueAdjuster.adjust(p, CorrectionMethod.BONFERRONI), EPS);
    }

    @Test
    void benjaminiHochbergMatchesR() {
        double[] expected = {0.025, 0.05, 0.05, 0.01, 0.05};
        assertArrayEquals(expected, PValueAdjuster.adjust(p, CorrectionMethod.BENJAMINI_HOCHBERG), 1e-14);
    }

    @Test
    void benjaminiYekutieliMatchesR() {
        double c5 = 1.0 + 1.0 / 2 + 1.0 / 3 + 1.0 / 4 + 1.0 / 5;
        double[] expected = {0.025 * c5, 0.05 * c5, 0.05 * c5, 0.01 * c5, 0.05 * c5};
        double[] actual = PValueAdjuster.adjust(p, CorrectionMethod.BENJAMINI_YEKUTIELI);
        assertArrayEquals(expected, actual, 1e-14);
        // adjusted values must never exceed 1
        assertTrue(Arrays.stream(actual).allMatch(v -> v >= 0.0 && v <= 1.0));
    }

    @Test
    void repeatedCallsAreIdentical() {
        double[] first = PValueAdjuster.adjust(p, CorrectionMethod.BENJAMINI_HOCHBERG);
        double[] second = PValueAdjuster.adjust(p, CorrectionMethod.BENJAMINI_HOCHBERG);
        assertArrayEquals(first, second, 0.0);
    }

    @Test
    void inputOrderIsPreserved() {
        // Same values in a different input order: results follow input positions.
        double[] reordered = {0.05, 0.002, 0.04, 0.01, 0.03};
        double[] actual = PValueAdjuster.adjust(reordered, CorrectionMethod.BENJAMINI_HOCHBERG);
        assertEquals(0.05, actual[0], EPS);
        assertEquals(0.01, actual[1], EPS);
        assertEquals(0.05, actual[2], EPS);
        assertEquals(0.025, actual[3], EPS);
        assertEquals(0.05, actual[4], EPS);
    }

    @Test
    void tiesReceiveIdenticalAdjustedValues() {
        double[] tied = {0.01, 0.01, 0.01};
        double[] actual = PValueAdjuster.adjust(tied, CorrectionMethod.BENJAMINI_HOCHBERG);
        assertEquals(actual[0], actual[1], 0.0);
        assertEquals(actual[1], actual[2], 0.0);
        assertEquals(0.01, actual[0], EPS);
    }

    @Test
    void neverExceedsOne() {
        double[] allLarge = {0.9, 0.8, 0.7};
        double[] actual = PValueAdjuster.adjust(allLarge, CorrectionMethod.BONFERRONI);
        assertTrue(Arrays.stream(actual).allMatch(v -> v <= 1.0));
    }
}
