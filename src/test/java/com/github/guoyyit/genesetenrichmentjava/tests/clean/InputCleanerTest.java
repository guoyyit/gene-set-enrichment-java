package com.github.guoyyit.genesetenrichmentjava.tests.clean;

import com.github.guoyyit.genesetenrichmentjava.core.clean.InputCleaner;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisParameters;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.core.model.CleanedInput;
import com.github.guoyyit.genesetenrichmentjava.core.model.CorrectionMethod;
import com.github.guoyyit.genesetenrichmentjava.core.model.IgnoredEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputCleanerTest {

    private static AnalysisParameters params(boolean caseSensitive) {
        return new AnalysisParameters(CorrectionMethod.BENJAMINI_HOCHBERG, 1, Integer.MAX_VALUE,
                caseSensitive, 60_000L);
    }

    private static AnalysisRequest request(List<String> bg, List<String> query,
                                           Map<String, List<String>> ann, boolean caseSensitive) {
        return new AnalysisRequest(bg, query, ann, params(caseSensitive));
    }

    private static Map<String, List<String>> annotations() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("cat1", List.of("GENE_A", "GENE_B", "GENE_C"));
        m.put("cat2", List.of("GENE_D", "GENE_E"));
        m.put("empty", List.of("NOT_IN_BG", "ALSO_NOT"));
        return m;
    }

    @Test
    void blanksAreTrimmedAndReported() {
        CleanedInput c = InputCleaner.clean(request(
                java.util.Arrays.asList("GENE_A", " ", null, "GENE_B"),
                List.of("GENE_A"),
                Map.of("cat1", List.of("GENE_A", "GENE_B")), false));
        assertEquals(2, c.background().size());
        assertTrue(c.ignored().stream().anyMatch(i -> i.reason() == IgnoredEntry.Reason.BLANK
                && " ".equals(i.value()) && i.source().equals("BACKGROUND")));
        assertTrue(c.ignored().stream().anyMatch(i -> i.reason() == IgnoredEntry.Reason.BLANK
                && i.value() == null));
    }

    @Test
    void duplicatesKeepFirstOccurrenceAndAreReportedInOrder() {
        CleanedInput c = InputCleaner.clean(request(
                List.of("GENE_A", "GENE_B", "GENE_A", "GENE_B"),
                List.of("GENE_A", "GENE_A"),
                Map.of("cat1", List.of("GENE_A")), false));
        assertEquals(2, c.background().size());
        assertEquals(1, c.query().size());
        List<IgnoredEntry> dups = c.ignored().stream()
                .filter(i -> i.reason() == IgnoredEntry.Reason.DUPLICATE).toList();
        assertEquals(3, dups.size());
        // encounter order preserved: background duplicates first, then query duplicate
        assertEquals("BACKGROUND", dups.get(0).source());
        assertEquals("QUERY", dups.get(2).source());
    }

    @Test
    void caseFoldsByDefaultAndMatchesAcrossCase() {
        CleanedInput c = InputCleaner.clean(request(
                List.of("gene_a", "gene_b"),
                List.of("GENE_A"),
                Map.of("cat1", List.of("Gene_A", "gene_B")), false));
        assertEquals(List.of("gene_a", "gene_b"), List.copyOf(c.background()));
        assertTrue(c.query().contains("gene_a"));
        assertTrue(c.annotations().get("cat1").contains("gene_a"));
        assertEquals(1, c.query().size());
    }

    @Test
    void caseSensitiveModeTreatsDifferentCaseAsUnknown() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> InputCleaner.clean(request(
                        List.of("GENE_A", "GENE_B"),
                        List.of("gene_a"),
                        Map.of("cat1", List.of("GENE_A")), true)));
        assertTrue(ex.getMessage().contains("no gene present in the background"));
    }

    @Test
    void unknownGenesInQueryAndAnnotationsAreReported() {
        CleanedInput c = InputCleaner.clean(request(
                List.of("GENE_A", "GENE_B", "GENE_C", "GENE_D", "GENE_E"),
                List.of("GENE_A", "MYSTERY"),
                annotations(), false));
        assertTrue(c.ignored().stream().anyMatch(i -> i.reason() == IgnoredEntry.Reason.UNKNOWN_GENE
                && "MYSTERY".equals(i.value()) && i.source().equals("QUERY")));
        assertTrue(c.ignored().stream().anyMatch(i -> i.reason() == IgnoredEntry.Reason.UNKNOWN_GENE
                && "NOT_IN_BG".equals(i.value()) && i.source().equals("ANNOTATION")));
    }

    @Test
    void emptyCategoriesAfterFilteringAreReportedAndDropped() {
        CleanedInput c = InputCleaner.clean(request(
                List.of("GENE_A", "GENE_B", "GENE_C", "GENE_D", "GENE_E"),
                List.of("GENE_A"),
                annotations(), false));
        assertTrue(c.ignored().stream().anyMatch(i -> i.reason() == IgnoredEntry.Reason.EMPTY_CATEGORY
                && "empty".equals(i.value())));
        assertEquals(2, c.annotations().size());
    }

    @Test
    void rejectedWhenQueryHasNoBackgroundGene() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> InputCleaner.clean(request(
                        List.of("GENE_A"), List.of("X", "Y"),
                        Map.of("cat1", List.of("GENE_A")), false)));
        assertTrue(ex.getMessage().contains("no gene present in the background"));
    }

    @Test
    void rejectsMissingParts() {
        assertThrows(IllegalArgumentException.class,
                () -> InputCleaner.clean(new AnalysisRequest(List.of(), List.of("A"),
                        Map.of("x", List.of("A")), params(false))));
        assertThrows(IllegalArgumentException.class,
                () -> InputCleaner.clean(new AnalysisRequest(List.of("A"), List.of(),
                        Map.of("x", List.of("A")), params(false))));
        assertThrows(IllegalArgumentException.class,
                () -> InputCleaner.clean(new AnalysisRequest(List.of("A"), List.of("A"),
                        Map.of(), params(false))));
    }

    @Test
    void annotationGenesAreRestrictedToBackground() {
        CleanedInput c = InputCleaner.clean(request(
                List.of("GENE_A"),
                List.of("GENE_A"),
                Map.of("cat1", List.of("GENE_A", "GENE_A", "GENE_X")), false));
        assertEquals(1, c.annotations().get("cat1").size());
    }
}
