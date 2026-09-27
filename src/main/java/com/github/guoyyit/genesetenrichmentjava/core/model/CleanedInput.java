package com.github.guoyyit.genesetenrichmentjava.core.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalized, deduplicated analysis input plus the list of ignored entries.
 * Gene identifiers are already normalized (trimmed, case-folded when requested).
 *
 * @param background   universe of genes (linked set, deterministic iteration order)
 * @param query        genes under analysis, subset of background after cleaning
 * @param annotations  category id -> genes of that category (restricted to background)
 * @param ignored      entries dropped during cleaning, in encounter order
 */
public record CleanedInput(
        Set<String> background,
        Set<String> query,
        Map<String, Set<String>> annotations,
        List<IgnoredEntry> ignored) {
}
