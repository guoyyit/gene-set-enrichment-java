package com.github.guoyyit.genesetenrichmentjava.tests.service;

import com.github.guoyyit.genesetenrichmentjava.model.AnalysisParams;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.model.CorrectionMethod;
import com.github.guoyyit.genesetenrichmentjava.service.AnalysisException;
import com.github.guoyyit.genesetenrichmentjava.service.CleanedInput;
import com.github.guoyyit.genesetenrichmentjava.service.InputCleaner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputCleanerTest {

    private final InputCleaner cleaner = new InputCleaner();

    private static AnalysisParams params() {
        return new AnalysisParams(CorrectionMethod.NONE, 1, Integer.MAX_VALUE, false, 5000L);
    }

    private static AnalysisRequest.GeneAnnotation ann(String gene, String category) {
        return new AnalysisRequest.GeneAnnotation(gene, category);
    }

    @Test
    void reportsBlanksDuplicatesCaseAndUnknownGenes() {
        List<String> background = new ArrayList<>(java.util.Arrays.asList(
                "brca1", "TP53", "  ", "brca1", "egfr"));
        List<String> query = new ArrayList<>(java.util.Arrays.asList(
                "BRCA1", "tp53", null, "zzz9", "brca1"));
        List<AnalysisRequest.GeneAnnotation> annotations = new ArrayList<>(java.util.Arrays.asList(
                ann("brca1", "DNA_REPAIR"),
                ann("BRCA1", "DNA_REPAIR"),
                ann("tp53", "DNA_REPAIR"),
                ann("egfr", "SIGNALING"),
                ann("zzz9", "GHOST"),
                ann(null, "X"),
                ann("egfr", "  "), null));

        CleanedInput cleaned = cleaner.clean(
                new AnalysisRequest(background, query, annotations, params()), params());

        // canonical background contains upper-cased unique genes
        assertEquals(java.util.Set.of("BRCA1", "TP53", "EGFR"), cleaned.background());

        // unknown query gene dropped
        assertEquals(java.util.Set.of("BRCA1", "TP53"), cleaned.query());
        assertTrue(cleaned.report().unknownQueryGenes().contains("ZZZ9"));

        // blanks reported (null pair entry, null gene, blank category)
        assertEquals(1, cleaned.report().blankBackgroundEntries().size());
        assertEquals(1, cleaned.report().blankQueryEntries().size());
        assertEquals(3, cleaned.report().blankAnnotationEntries().size());

        // duplicates reported
        assertTrue(cleaned.report().duplicateBackgroundGenes().contains("BRCA1"));
        assertTrue(cleaned.report().duplicateQueryGenes().contains("BRCA1"));
        assertTrue(cleaned.report().duplicateAnnotations().contains("BRCA1:DNA_REPAIR"));

        // case-normalized raw forms reported
        assertTrue(cleaned.report().caseNormalizedBackgroundGenes().contains("brca1"));
        assertTrue(cleaned.report().caseNormalizedAnnotationGenes().contains("brca1"));

        // unknown annotation gene dropped; its category removed entirely
        assertTrue(cleaned.report().unknownAnnotationGenes().contains("ZZZ9"));
        assertEquals(2, cleaned.annotations().size());
        assertEquals(java.util.Set.of("BRCA1", "TP53"),
                cleaned.annotations().get("DNA_REPAIR"));
    }

    @Test
    void caseSensitiveModeKeepsSymbolsDistinct() {
        AnalysisParams cs = new AnalysisParams(CorrectionMethod.NONE, 1,
                Integer.MAX_VALUE, true, 5000L);
        List<String> background = List.of("abc", "ABC");
        List<String> query = List.of("abc");
        CleanedInput cleaned = cleaner.clean(
                new AnalysisRequest(background, query, List.of(), cs), cs);
        assertEquals(2, cleaned.background().size());
        assertEquals(1, cleaned.query().size());
        assertTrue(cleaned.report().caseNormalizedBackgroundGenes().isEmpty());
    }

    @Test
    void filtersCategoriesBySize() {
        AnalysisParams p = new AnalysisParams(CorrectionMethod.NONE, 2, 2, false, 5000L);
        List<String> background = List.of("A", "B", "C");
        List<String> query = List.of("A");
        List<AnalysisRequest.GeneAnnotation> annotations = List.of(
                ann("A", "BIG"), ann("B", "BIG"), ann("C", "BIG"),
                ann("A", "RIGHT"), ann("B", "RIGHT"));
        CleanedInput cleaned = cleaner.clean(
                new AnalysisRequest(background, query, annotations, p), p);
        assertEquals(1, cleaned.annotations().size());
        assertTrue(cleaned.annotations().containsKey("RIGHT"));
        assertTrue(cleaned.report().sizeFilteredCategories().get(0).startsWith("BIG"));
    }

    @Test
    void rejectsEmptyBackground() {
        AnalysisException ex = assertThrows(AnalysisException.class,
                () -> cleaner.clean(new AnalysisRequest(List.of(" ", ""),
                        List.of("A"), List.of(), params()), params()));
        assertEquals("INVALID_INPUT", ex.code());
    }

    @Test
    void rejectsQueryWithNoKnownGenes() {
        AnalysisException ex = assertThrows(AnalysisException.class,
                () -> cleaner.clean(new AnalysisRequest(List.of("A"),
                        List.of("B", "C"), List.of(), params()), params()));
        assertEquals("INVALID_INPUT", ex.code());
    }
}
