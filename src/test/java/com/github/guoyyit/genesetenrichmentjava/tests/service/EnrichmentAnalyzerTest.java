package com.github.guoyyit.genesetenrichmentjava.tests.service;

import com.github.guoyyit.genesetenrichmentjava.model.AnalysisParams;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisResult;
import com.github.guoyyit.genesetenrichmentjava.model.CategoryResult;
import com.github.guoyyit.genesetenrichmentjava.model.CorrectionMethod;
import com.github.guoyyit.genesetenrichmentjava.model.EnrichmentDirection;
import com.github.guoyyit.genesetenrichmentjava.service.EnrichmentAnalyzer;
import com.github.guoyyit.genesetenrichmentjava.service.InputCleaner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichmentAnalyzerTest {

    private final EnrichmentAnalyzer analyzer = new EnrichmentAnalyzer(new InputCleaner());

    private static AnalysisRequest.GeneAnnotation ann(String g, String c) {
        return new AnalysisRequest.GeneAnnotation(g, c);
    }

    private AnalysisRequest smallRequest() {
        return new AnalysisRequest(
                List.of("G1", "G2", "G3", "G4", "G5", "G6", "G7", "G8", "G9", "G10"),
                List.of("G1", "G2", "G3", "G4"),
                List.of(
                        ann("G1", "CAT_A"), ann("G2", "CAT_A"),
                        ann("G3", "CAT_A"), ann("G8", "CAT_A"),
                        ann("G4", "CAT_B"), ann("G9", "CAT_B"),
                        ann("G5", "CAT_C"), ann("G6", "CAT_C"),
                        ann("G7", "CAT_C"), ann("G10", "CAT_C")),
                null);
    }

    @Test
    void computesCorrectStatistics() {
        AnalysisParams params = AnalysisParams.defaults();
        AnalysisResult result = analyzer.analyze(smallRequest(), params, () -> { });

        assertEquals(10, result.backgroundSize());
        assertEquals(4, result.querySize());
        assertEquals(3, result.categoryCount());
        // params echoed back for traceability
        assertEquals(CorrectionMethod.BENJAMINI_HOCHBERG,
                result.effectiveParams().correctionMethod());

        // CAT_C (k=0, lower-tail 1/14) is the most significant, then CAT_A (5/42)
        assertEquals("CAT_C", result.categories().get(0).category());
        assertEquals("CAT_A", result.categories().get(1).category());
        assertEquals("CAT_B", result.categories().get(2).category());

        CategoryResult a = result.categories().get(1);
        assertEquals(4, a.categorySize());
        assertEquals(3, a.intersectionSize());
        assertEquals(1.875, a.foldEnrichment(), 1e-12);
        assertEquals(EnrichmentDirection.OVER_REPRESENTED, a.direction());
        // P(X>=3) for Hypergeometric(10,4,4) = 5/42
        assertEquals(5.0 / 42.0, a.pValue(), 1e-12);
        // adjusted >= raw, <= 1
        assertTrue(a.adjustedPValue() >= a.pValue() - 1e-12);
        assertTrue(a.adjustedPValue() <= 1.0);

        CategoryResult c = result.categories().get(0);
        assertEquals(0, c.intersectionSize());
        assertEquals(0.0, c.foldEnrichment(), 0.0);
        assertEquals(EnrichmentDirection.UNDER_REPRESENTED, c.direction());
        assertEquals(1.0 / 14.0, c.pValue(), 1e-12);
    }

    @Test
    void identicalInputProducesIdenticalOutputAcrossRuns() {
        AnalysisParams params = new AnalysisParams(CorrectionMethod.HOLM, 1,
                Integer.MAX_VALUE, false, 10000L);
        AnalysisResult first = analyzer.analyze(smallRequest(), params, () -> { });
        for (int i = 0; i < 50; i++) {
            AnalysisResult other = analyzer.analyze(smallRequest(), params, () -> { });
            assertEquals(first.categories(), other.categories(),
                    "run " + i + " differs deterministically");
        }
    }

    @Test
    void noSharedStateBetweenDifferentRequests() {
        AnalysisRequest other = new AnalysisRequest(
                List.of("A", "B", "C"),
                List.of("A", "B"),
                List.of(ann("A", "X"), ann("B", "X")),
                null);
        AnalysisResult r1 = analyzer.analyze(smallRequest(), AnalysisParams.defaults(), () -> { });
        AnalysisResult r2 = analyzer.analyze(other, AnalysisParams.defaults(), () -> { });
        AnalysisResult r1Again = analyzer.analyze(smallRequest(), AnalysisParams.defaults(), () -> { });

        assertEquals(3, r1.categoryCount());
        assertEquals(1, r2.categories().size());
        assertEquals(r1.categories(), r1Again.categories());
        assertEquals(3, r1.categoryCount());
    }
}
