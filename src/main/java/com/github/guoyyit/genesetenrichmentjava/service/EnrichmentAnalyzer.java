package com.github.guoyyit.genesetenrichmentjava.service;

import com.github.guoyyit.genesetenrichmentjava.model.AnalysisParams;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisResult;
import com.github.guoyyit.genesetenrichmentjava.model.CategoryResult;
import com.github.guoyyit.genesetenrichmentjava.model.EnrichmentDirection;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Runs the full enrichment analysis: cleaning, per-category hypergeometric
 * tests and multiple testing correction. Pure function of input and
 * parameters, with cancellation checks.
 *
 * <p>Statistical convention (documented in README): for each category of size
 * K in a background of N, with a query of size n and k observed hits, the
 * fold enrichment is (k/n)/(K/N). Direction is OVER_REPRESENTED when k/n
 * &gt;= K/N and UNDER_REPRESENTED otherwise; the reported p-value is the
 * matching one-sided hypergeometric tail (upper for over, lower for under).
 * Results are ordered by (p-value ascending, adjusted p-value ascending,
 * category name ascending), so identical input/parameters always produce
 * identical output.
 */
@Component
public class EnrichmentAnalyzer {

    private static final int CANCELLATION_CHECK_PERIOD = 256;

    private final InputCleaner inputCleaner;

    public EnrichmentAnalyzer(InputCleaner inputCleaner) {
        this.inputCleaner = inputCleaner;
    }

    public AnalysisResult analyze(AnalysisRequest request, AnalysisParams effectiveParams,
                                  CancellationChecker cancellationChecker) {
        CancellationChecker checker = cancellationChecker == null
                ? () -> { } : cancellationChecker;
        long startNanos = System.nanoTime();
        Instant startedAt = Instant.now();
        checker.check();

        CleanedInput cleaned = inputCleaner.clean(request, effectiveParams);
        checker.check();

        int n = cleaned.background().size();
        int drawCount = cleaned.query().size();

        // Per-category raw statistics.
        List<CategoryResult> raw = new ArrayList<>(cleaned.annotations().size());
        List<Double> pValues = new ArrayList<>(cleaned.annotations().size());
        int processed = 0;
        for (var entry : cleaned.annotations().entrySet()) {
            if ((processed++ & (CANCELLATION_CHECK_PERIOD - 1)) == 0) {
                checker.check();
            }
            String category = entry.getKey();
            TreeSet<String> categoryGenes = entry.getValue();
            int categorySize = categoryGenes.size();
            int hits = 0;
            for (String gene : cleaned.query()) {
                if (categoryGenes.contains(gene)) {
                    hits++;
                }
            }
            double fold = Hypergeometric.foldEnrichment(n, categorySize, drawCount, hits);
            double expected = (double) drawCount * categorySize / n;
            boolean over = hits >= expected;
            double pValue = over
                    ? Hypergeometric.upperTailPValue(n, categorySize, drawCount, hits)
                    : Hypergeometric.lowerTailPValue(n, categorySize, drawCount, hits);
            raw.add(new CategoryResult(category, n, drawCount, categorySize, hits,
                    fold, over ? EnrichmentDirection.OVER_REPRESENTED
                            : EnrichmentDirection.UNDER_REPRESENTED,
                    pValue, Double.NaN));
            pValues.add(pValue);
        }
        checker.check();

        List<Double> adjusted = PValueAdjuster.adjust(
                pValues, effectiveParams.correctionMethod());
        List<CategoryResult> results = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            CategoryResult r = raw.get(i);
            results.add(new CategoryResult(r.category(), r.backgroundSize(), r.querySize(),
                    r.categorySize(), r.intersectionSize(), r.foldEnrichment(),
                    r.direction(), r.pValue(), adjusted.get(i)));
        }

        results.sort(Comparator
                .comparingDouble(CategoryResult::pValue)
                .thenComparingDouble(CategoryResult::adjustedPValue)
                .thenComparing(CategoryResult::category));

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        return new AnalysisResult(effectiveParams, cleaned.report(), n, drawCount,
                results.size(), List.copyOf(results), startedAt, durationMs);
    }
}
