package com.github.guoyyit.genesetenrichmentjava.core.clean;

import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisParameters;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.core.model.CleanedInput;
import com.github.guoyyit.genesetenrichmentjava.core.model.IgnoredEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes raw requests: trims entries, folds case when requested, drops
 * blanks, duplicates and unknown genes, and records every dropped entry.
 * Stateless and thread-safe.
 */
public final class InputCleaner {

    private static final String SRC_BACKGROUND = "BACKGROUND";
    private static final String SRC_QUERY = "QUERY";
    private static final String SRC_ANNOTATION = "ANNOTATION";

    private InputCleaner() {
    }

    public static CleanedInput clean(AnalysisRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (request.background() == null || request.background().isEmpty()) {
            throw new IllegalArgumentException("background must be a non-empty list");
        }
        if (request.query() == null || request.query().isEmpty()) {
            throw new IllegalArgumentException("query must be a non-empty list");
        }
        if (request.annotations() == null || request.annotations().isEmpty()) {
            throw new IllegalArgumentException("annotations must be a non-empty map");
        }

        AnalysisParameters params = request.effectiveParameters();
        List<IgnoredEntry> ignored = new ArrayList<>();

        Set<String> background = cleanGeneList(request.background(), params, SRC_BACKGROUND, ignored, null);

        Set<String> query = cleanGeneList(request.query(), params, SRC_QUERY, ignored, background);
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query has no gene present in the background after cleaning");
        }

        Map<String, Set<String>> annotations = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : request.annotations().entrySet()) {
            String categoryId = entry.getKey();
            if (categoryId == null || categoryId.isBlank()) {
                throw new IllegalArgumentException("annotation category id must be non-blank");
            }
            Set<String> genes = cleanGeneList(
                    entry.getValue() == null ? List.of() : entry.getValue(),
                    params, SRC_ANNOTATION, ignored, background);
            if (genes.isEmpty()) {
                ignored.add(new IgnoredEntry(SRC_ANNOTATION, categoryId, IgnoredEntry.Reason.EMPTY_CATEGORY));
                continue;
            }
            annotations.put(categoryId, genes);
        }
        if (annotations.isEmpty()) {
            throw new IllegalArgumentException("no annotation category has any gene present in the background");
        }

        return new CleanedInput(background, query, annotations, List.copyOf(ignored));
    }

    /**
     * Cleans one gene list. When {@code knownUniverse} is non-null, genes not
     * present in it are dropped as UNKNOWN_GENE.
     */
    private static Set<String> cleanGeneList(List<String> raw, AnalysisParameters params, String source,
                                             List<IgnoredEntry> ignored, Set<String> knownUniverse) {
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : raw) {
            String normalized = normalize(value, params.caseSensitive());
            if (normalized == null) {
                ignored.add(new IgnoredEntry(source, value, IgnoredEntry.Reason.BLANK));
                continue;
            }
            if (!cleaned.add(normalized)) {
                ignored.add(new IgnoredEntry(source, value, IgnoredEntry.Reason.DUPLICATE));
                continue;
            }
            if (knownUniverse != null && !knownUniverse.contains(normalized)) {
                cleaned.remove(normalized);
                ignored.add(new IgnoredEntry(source, value, IgnoredEntry.Reason.UNKNOWN_GENE));
            }
        }
        return cleaned;
    }

    private static String normalize(String value, boolean caseSensitive) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return caseSensitive ? trimmed : trimmed.toLowerCase(Locale.ROOT);
    }
}
