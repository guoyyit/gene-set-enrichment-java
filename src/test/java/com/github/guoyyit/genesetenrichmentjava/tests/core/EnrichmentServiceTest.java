package com.github.guoyyit.genesetenrichmentjava.tests.core;

import com.github.guoyyit.genesetenrichmentjava.core.AnalysisCancelledException;
import com.github.guoyyit.genesetenrichmentjava.core.EnrichmentService;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisParameters;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisResult;
import com.github.guoyyit.genesetenrichmentjava.core.model.CategoryResult;
import com.github.guoyyit.genesetenrichmentjava.core.model.CorrectionMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichmentServiceTest {

    private final EnrichmentService service = new EnrichmentService();

    private static AnalysisParameters params(CorrectionMethod method) {
        return new AnalysisParameters(method, 1, Integer.MAX_VALUE, false, 0L);
    }

    private static CategoryResult byId(AnalysisResult result, String id) {
        return result.categories().stream()
                .filter(c -> c.categoryId().equals(id))
                .findFirst().orElseThrow();
    }

    /** 100 genes: 10 in category A (g0..g9), query = exactly the 10 A genes. */
    private static AnalysisRequest fullyEnrichedRequest() {
        List<String> bg = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            bg.add("g" + i);
        }
        List<String> query = bg.subList(0, 10).stream().toList();
        Map<String, List<String>> ann = new LinkedHashMap<>();
        ann.put("A", bg.subList(0, 10).stream().toList());
        ann.put("B", bg.subList(50, 60).stream().toList());
        return new AnalysisRequest(bg, query, ann, params(CorrectionMethod.BENJAMINI_HOCHBERG));
    }

    @Test
    void perfectOverlapIsSignificantWithFoldOneDividedByExpected() {
        AnalysisResult result = service.analyze("t1", fullyEnrichedRequest());
        CategoryResult a = byId(result, "A");
        assertEquals(10, a.categorySize());
        assertEquals(10, a.intersectionSize());
        // fold = 1 / (10/100) = 10
        assertEquals(10.0, a.foldEnrichment(), 1e-12);
        // P(X = 10) = 1 / C(100,10), extremely small
        assertTrue(a.pValue() < 1e-10, "p=" + a.pValue());
        assertEquals("OVER_REPRESENTED", a.direction().name());
        // strongest category sorts first
        assertEquals("A", result.categories().get(0).categoryId());

        CategoryResult b = byId(result, "B");
        assertEquals(0, b.intersectionSize());
        assertEquals(0.0, b.foldEnrichment(), 0.0);
        assertEquals("UNDER_REPRESENTED", b.direction().name());
        // zero overlap but expected overlap is 1: not significant, just non-enriched
        assertTrue(b.pValue() > 0.0 && b.pValue() < 1.0);
    }

    @Test
    void repeatedRunsProduceBytewiseIdenticalOutput() {
        AnalysisRequest r1 = fullyEnrichedRequest();
        AnalysisResult first = service.analyze("t", r1);
        AnalysisResult second = service.analyze("t", r1);
        assertEquals(first, second);
    }

    @Test
    void shuffledInputDoesNotChangeOutput() {
        AnalysisRequest original = fullyEnrichedRequest();

        List<String> bg = new ArrayList<>(original.background());
        List<String> q = new ArrayList<>(original.query());
        java.util.Collections.reverse(bg);
        java.util.Collections.shuffle(q, new java.util.Random(42));
        Map<String, List<String>> shuffledAnn = new HashMap<>();
        original.annotations().forEach((k, v) -> {
            List<String> genes = new ArrayList<>(v);
            java.util.Collections.shuffle(genes, new java.util.Random(7));
            shuffledAnn.put(k, genes);
        });
        AnalysisRequest shuffled = new AnalysisRequest(bg, q, shuffledAnn, original.parameters());

        AnalysisResult a = service.analyze("t", original);
        AnalysisResult b = service.analyze("t", shuffled);
        assertEquals(a.categories(), b.categories());
    }

    @Test
    void differentParametersProduceDifferentCorrections() {
        AnalysisRequest req = fullyEnrichedRequest();
        AnalysisResult bh = service.analyze("t",
                new AnalysisRequest(req.background(), req.query(), req.annotations(),
                        params(CorrectionMethod.BENJAMINI_HOCHBERG)));
        AnalysisResult bonf = service.analyze("t",
                new AnalysisRequest(req.background(), req.query(), req.annotations(),
                        params(CorrectionMethod.BONFERRONI)));
        // parameters are echoed into the result for traceability
        assertEquals(CorrectionMethod.BENJAMINI_HOCHBERG, bh.parameters().correctionMethod());
        assertEquals(CorrectionMethod.BONFERRONI, bonf.parameters().correctionMethod());
        // with 2 categories, bonferroni doubles the largest p-value while BH keeps it;
        // raw p-values and all other fields stay identical
        assertEquals(bh.categories().get(1).pValue() * 2,
                bonf.categories().get(1).adjustedPValue(), 1e-14);
        assertEquals(bh.categories().get(1).pValue(),
                bh.categories().get(1).adjustedPValue(), 0.0);
        assertEquals(bh.categories().get(0).pValue(), bonf.categories().get(0).pValue(), 0.0);
    }

    @Test
    void minAndMaxCategorySizeAreHonored() {
        AnalysisParameters p = new AnalysisParameters(CorrectionMethod.BENJAMINI_HOCHBERG, 5, 9, false, 0L);
        AnalysisRequest req = new AnalysisRequest(
                fullyEnrichedRequest().background(),
                fullyEnrichedRequest().query(),
                fullyEnrichedRequest().annotations(), p);
        AnalysisResult result = service.analyze("t", req);
        // A and B both have size 10 and are filtered out by maxCategorySize=9
        assertEquals(0, result.categories().size());
    }

    @Test
    void underRepresentedCategoryReportsLowerTail() {
        // N=10, category size 5, query size 4, intersection 0:
        // P(X <= 0) = C(5,0)C(5,4)/C(10,4) = 5/210
        List<String> bg = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        Map<String, List<String>> ann = Map.of("cat", List.of("a", "b", "c", "d", "e"));
        AnalysisRequest req = new AnalysisRequest(bg, List.of("f", "g", "h", "i"), ann,
                params(CorrectionMethod.BONFERRONI));
        CategoryResult r = service.analyze("t", req).categories().get(0);
        assertEquals(5.0 / 210.0, r.pValue(), 1e-14);
        assertEquals("UNDER_REPRESENTED", r.direction().name());
    }

    @Test
    void interruptedRunThrowsAndReturnsNoPartialResult() {
        // Large annotation count makes the per-category loop observe interruption.
        List<String> bg = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            bg.add("g" + i);
        }
        List<String> query = bg.subList(0, 2_500).stream().toList();
        Map<String, List<String>> ann = new LinkedHashMap<>();
        for (int c = 0; c < 50_000; c++) {
            ann.put("cat" + c, List.of("g" + (c % 5_000)));
        }
        AnalysisRequest req = new AnalysisRequest(bg, query, ann, params(CorrectionMethod.BONFERRONI));

        Thread runner = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            runner.interrupt();
        });
        interrupter.start();
        assertThrows(AnalysisCancelledException.class, () -> service.analyze("t", req));
        // interruption flag must have been cleared by the throwing path
        assertTrue(!Thread.currentThread().isInterrupted());
    }
}
