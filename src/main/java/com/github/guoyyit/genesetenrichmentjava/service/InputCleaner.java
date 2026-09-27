package com.github.guoyyit.genesetenrichmentjava.service;

import com.github.guoyyit.genesetenrichmentjava.model.AnalysisParams;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.model.IgnoredInputReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cleans caller-supplied input: removes blanks/duplicates, optionally
 * normalizes case, drops unknown genes and produces an explicit report.
 */
@Component
public class InputCleaner {

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static List<String> sortedDistinct(List<String> values) {
        return new ArrayList<>(new TreeSet<>(values));
    }

    private record GeneClean(
            LinkedHashSet<String> genes,
            List<String> blank,
            List<String> duplicates,
            List<String> caseNormalized) {
    }

    /**
     * Cleans a plain gene list into canonical unique non-blank genes.
     */
    private GeneClean cleanGeneList(List<String> rawGenes, boolean caseSensitive) {
        List<String> safe = rawGenes == null ? List.of() : rawGenes;
        List<String> blanks = new ArrayList<>();
        // canonical gene -> distinct raw trimmed forms actually observed
        Map<String, TreeSet<String>> rawForms = new LinkedHashMap<>();
        java.util.Set<String> canonical = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();

        for (String raw : safe) {
            if (isBlank(raw)) {
                blanks.add(raw == null ? "<null>" : "<blank len=" + raw.length() + ">");
                continue;
            }
            String trimmed = raw.trim();
            String canonicalGene = caseSensitive ? trimmed : trimmed.toUpperCase(Locale.ROOT);
            if (!canonical.add(canonicalGene)) {
                duplicates.add(canonicalGene);
            }
            rawForms.computeIfAbsent(canonicalGene, k -> new TreeSet<>()).add(trimmed);
        }

        List<String> caseNormalized = new ArrayList<>();
        rawForms.forEach((canonicalGene, forms) -> {
            for (String form : forms) {
                if (!form.equals(canonicalGene)) {
                    caseNormalized.add(form);
                }
            }
        });

        return new GeneClean(
                new LinkedHashSet<>(new TreeSet<>(canonical)),
                List.copyOf(blanks),
                sortedDistinct(duplicates),
                sortedDistinct(caseNormalized));
    }

    public CleanedInput clean(AnalysisRequest request, AnalysisParams effectiveParams) {
        if (request == null) {
            throw new AnalysisException("INVALID_INPUT", "request body is required");
        }
        boolean caseSensitive = effectiveParams.caseSensitive();
        GeneClean backgroundClean = cleanGeneList(request.background(), caseSensitive);
        GeneClean queryCleanRaw = cleanGeneList(request.query(), caseSensitive);

        if (backgroundClean.genes().isEmpty()) {
            throw new AnalysisException("INVALID_INPUT",
                    "background set contains no usable genes after cleaning");
        }

        // Drop query genes unknown to the background.
        TreeSet<String> unknownQuery = new TreeSet<>();
        LinkedHashSet<String> queryGenes = new LinkedHashSet<>();
        for (String gene : queryCleanRaw.genes()) {
            if (backgroundClean.genes().contains(gene)) {
                queryGenes.add(gene);
            } else {
                unknownQuery.add(gene);
            }
        }
        if (queryGenes.isEmpty()) {
            throw new AnalysisException("INVALID_INPUT",
                    "query set contains no genes present in the background after cleaning");
        }

        List<AnalysisRequest.GeneAnnotation> annotations =
                request.annotations() == null ? List.of() : request.annotations();
        List<String> blankAnnotations = new ArrayList<>();
        List<String> duplicateAnnotations = new ArrayList<>();
        TreeSet<String> unknownAnnotationGenes = new TreeSet<>();
        TreeSet<String> caseNormalizedAnnotationGenes = new TreeSet<>();
        // category -> distinct canonical genes (known to background only)
        TreeMap<String, TreeSet<String>> categoryToGenes = new TreeMap<>();
        java.util.Set<String> seenPairs = new java.util.HashSet<>();

        for (AnalysisRequest.GeneAnnotation entry : annotations) {
            String rawGene = entry == null ? null : entry.gene();
            String rawCategory = entry == null ? null : entry.category();
            if (isBlank(rawGene) || isBlank(rawCategory)) {
                blankAnnotations.add("(gene=" + String.valueOf(rawGene)
                        + ", category=" + String.valueOf(rawCategory) + ")");
                continue;
            }
            String gene = rawGene.trim();
            String canonicalGene = caseSensitive ? gene : gene.toUpperCase(Locale.ROOT);
            String category = rawCategory.trim();

            if (!gene.equals(canonicalGene)) {
                caseNormalizedAnnotationGenes.add(gene);
            }
            if (!backgroundClean.genes().contains(canonicalGene)) {
                unknownAnnotationGenes.add(canonicalGene);
                continue;
            }
            String pair = canonicalGene + "|" + category;
            if (!seenPairs.add(pair)) {
                duplicateAnnotations.add(canonicalGene + ":" + category);
                continue;
            }
            categoryToGenes.computeIfAbsent(category, k -> new TreeSet<>()).add(canonicalGene);
        }

        // Apply category size filters.
        TreeSet<String> sizeFiltered = new TreeSet<>();
        TreeMap<String, TreeSet<String>> retained = new TreeMap<>();
        int min = effectiveParams.minCategorySize();
        int max = effectiveParams.maxCategorySize();
        for (Map.Entry<String, TreeSet<String>> entry : categoryToGenes.entrySet()) {
            int size = entry.getValue().size();
            if (size < min || size > max) {
                sizeFiltered.add(entry.getKey() + "(size=" + size + ")");
            } else {
                retained.put(entry.getKey(), entry.getValue());
            }
        }

        IgnoredInputReport report = new IgnoredInputReport(
                sortedDistinct(backgroundClean.blank()),
                sortedDistinct(queryCleanRaw.blank()),
                backgroundClean.duplicates(),
                queryCleanRaw.duplicates(),
                backgroundClean.caseNormalized(),
                queryCleanRaw.caseNormalized(),
                new ArrayList<>(unknownQuery),
                sortedDistinct(blankAnnotations),
                new ArrayList<>(caseNormalizedAnnotationGenes),
                new ArrayList<>(unknownAnnotationGenes),
                sortedDistinct(duplicateAnnotations),
                new ArrayList<>(sizeFiltered));

        return new CleanedInput(
                backgroundClean.genes(),
                queryGenes,
                retained,
                report);
    }
}
