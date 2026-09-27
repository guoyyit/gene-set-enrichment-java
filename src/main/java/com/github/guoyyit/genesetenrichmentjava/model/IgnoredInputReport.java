package com.github.guoyyit.genesetenrichmentjava.model;

import java.util.List;

/**
 * Report of every input entry that was ignored or normalized during cleaning.
 * All lists are sorted so the report is deterministic for identical input.
 */
public record IgnoredInputReport(
        List<String> blankBackgroundEntries,
        List<String> blankQueryEntries,
        List<String> duplicateBackgroundGenes,
        List<String> duplicateQueryGenes,
        List<String> caseNormalizedBackgroundGenes,
        List<String> caseNormalizedQueryGenes,
        List<String> unknownQueryGenes,
        List<String> blankAnnotationEntries,
        List<String> caseNormalizedAnnotationGenes,
        List<String> unknownAnnotationGenes,
        List<String> duplicateAnnotations,
        List<String> sizeFilteredCategories) {

    public static IgnoredInputReport empty() {
        return new IgnoredInputReport(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
