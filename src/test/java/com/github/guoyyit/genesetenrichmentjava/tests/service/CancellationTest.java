package com.github.guoyyit.genesetenrichmentjava.tests.service;

import com.github.guoyyit.genesetenrichmentjava.model.AnalysisParams;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.model.CorrectionMethod;
import com.github.guoyyit.genesetenrichmentjava.service.AnalysisCancelledException;
import com.github.guoyyit.genesetenrichmentjava.service.CancellationChecker;
import com.github.guoyyit.genesetenrichmentjava.service.EnrichmentAnalyzer;
import com.github.guoyyit.genesetenrichmentjava.service.InputCleaner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTest {

    private final EnrichmentAnalyzer analyzer = new EnrichmentAnalyzer(new InputCleaner());

    private AnalysisRequest largeRequest(int categories) {
        List<String> background = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            background.add("G" + i);
        }
        List<String> query = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            query.add("G" + i);
        }
        List<AnalysisRequest.GeneAnnotation> annotations = new ArrayList<>();
        for (int c = 0; c < categories; c++) {
            for (int g = 0; g < 10; g++) {
                annotations.add(new AnalysisRequest.GeneAnnotation(
                        "G" + ((c * 7 + g) % 2000), "CAT_" + c));
            }
        }
        AnalysisParams params = new AnalysisParams(CorrectionMethod.NONE, 1,
                Integer.MAX_VALUE, false, 60_000L);
        return new AnalysisRequest(background, query, annotations, params);
    }

    @Test
    void cancelledAnalysisThrowsAndReturnsNoResult() {
        AtomicBoolean returnedResult = new AtomicBoolean(false);
        CancellationChecker alwaysCancel = () -> {
            throw new AnalysisCancelledException("CANCELLED", "cancelled");
        };
        AnalysisCancelledException ex = assertThrows(AnalysisCancelledException.class,
                () -> {
                    var result = analyzer.analyze(
                            largeRequest(5000), AnalysisParams.defaults(), alwaysCancel);
                    returnedResult.set(true);
                });
        assertEquals("CANCELLED", ex.code());
        assertFalse(returnedResult.get(), "no partial result must be returned on cancel");
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    @Test
    void cancellationMidwayThroughCategoriesIsHonored() {
        int[] checks = {0};
        CancellationChecker afterFewChecks = () -> {
            if (checks[0]++ > 3) {
                throw new AnalysisCancelledException("CANCELLED", "cancelled midway");
            }
        };
        assertThrows(AnalysisCancelledException.class,
                () -> analyzer.analyze(largeRequest(100_000),
                        AnalysisParams.defaults(), afterFewChecks));
        assertTrue(checks[0] > 3);
    }

    @Test
    void timeoutCheckerTriggersBeyondDeadline() throws Exception {
        long deadline = System.nanoTime() + 50_000_000L;
        CancellationChecker timeout = () -> {
            if (System.nanoTime() > deadline) {
                throw new AnalysisCancelledException("JOB_TIMEOUT", "timeout");
            }
        };
        Thread.sleep(80);
        AnalysisCancelledException ex = assertThrows(AnalysisCancelledException.class,
                () -> timeout.check());
        assertEquals("JOB_TIMEOUT", ex.code());
    }
}
