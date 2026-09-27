package com.github.guoyyit.genesetenrichmentjava.tests.service;

import com.github.guoyyit.genesetenrichmentjava.model.CorrectionMethod;
import com.github.guoyyit.genesetenrichmentjava.service.PValueAdjuster;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PValueAdjusterTest {

    private static final double EPS = 1e-12;

    @Test
    void noneIsPassThrough() {
        List<Double> p = List.of(0.01, 0.5, 0.9);
        assertEquals(p, PValueAdjuster.adjust(p, CorrectionMethod.NONE));
    }

    @Test
    void bonferroniMultipliesByCount() {
        List<Double> adj = PValueAdjuster.adjust(
                List.of(0.01, 0.02, 0.5), CorrectionMethod.BONFERRONI);
        assertEquals(0.03, adj.get(0), EPS);
        assertEquals(0.06, adj.get(1), EPS);
        assertEquals(1.0, adj.get(2), EPS);
    }

    @Test
    void holmIsMonotonicStepDown() {
        List<Double> adj = PValueAdjuster.adjust(
                List.of(0.01, 0.01, 0.5), CorrectionMethod.HOLM);
        // ranks: 0 -> 3*0.01, 1 -> 2*0.01, 2 -> 1*0.5 capped at 1
        assertEquals(0.03, adj.get(0), EPS);
        assertEquals(0.03, adj.get(1), EPS);
        assertEquals(0.5, adj.get(2), EPS);
        // monotonic in sorted order
        assertTrue(adj.get(0) <= adj.get(2));
    }

    @Test
    void bhIsStandardStepUp() {
        // textbook example
        List<Double> p = List.of(0.01, 0.04, 0.03, 0.02);
        List<Double> adj = PValueAdjuster.adjust(p, CorrectionMethod.BENJAMINI_HOCHBERG);
        // sorted: .01,.02,.03,.04; raw q: .04,.04,.04,.04 -> all 0.04
        for (double q : adj) {
            assertEquals(0.04, q, EPS);
        }
    }

    @Test
    void bhAndByDifferByHarmonicScale() {
        List<Double> p = List.of(0.01, 0.02, 0.03);
        List<Double> bh = PValueAdjuster.adjust(p, CorrectionMethod.BENJAMINI_HOCHBERG);
        List<Double> by = PValueAdjuster.adjust(p, CorrectionMethod.BENJAMINI_YEKUTIELI);
        double c = 1.0 + 1.0 / 2 + 1.0 / 3;
        for (int i = 0; i < p.size(); i++) {
            assertEquals(Math.min(1.0, bh.get(i) * c), by.get(i), 1e-12);
        }
    }

    @Test
    void isDeterministicAcrossRepeatedCalls() {
        List<Double> p = List.of(0.3, 0.001, 0.12, 0.5, 0.0004, 0.99);
        List<Double> first = PValueAdjuster.adjust(p, CorrectionMethod.HOLM);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, PValueAdjuster.adjust(p, CorrectionMethod.HOLM));
        }
    }

    @Test
    void handlesClampingAndNulls() {
        List<Double> adj = PValueAdjuster.adjust(
                java.util.Arrays.asList(0.8, null, 1.2, -0.1), CorrectionMethod.BONFERRONI);
        assertEquals(1.0, adj.get(0), EPS);
        assertEquals(1.0, adj.get(1), EPS);
        assertEquals(1.0, adj.get(2), EPS);
        assertEquals(0.0, adj.get(3), EPS);
    }
}
