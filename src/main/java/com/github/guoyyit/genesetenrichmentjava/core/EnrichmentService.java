package com.github.guoyyit.genesetenrichmentjava.core;

import com.github.guoyyit.genesetenrichmentjava.core.clean.InputCleaner;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisParameters;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisResult;
import com.github.guoyyit.genesetenrichmentjava.core.model.CategoryResult;
import com.github.guoyyit.genesetenrichmentjava.core.model.CleanedInput;
import com.github.guoyyit.genesetenrichmentjava.core.model.EnrichmentDirection;
import com.github.guoyyit.genesetenrichmentjava.core.stats.Hypergeometric;
import com.github.guoyyit.genesetenrichmentjava.core.stats.PValueAdjuster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a single enrichment analysis synchronously. Cancellation is observed
 * through the interrupt flag of the calling thread.
 */
public class EnrichmentService {

    /**
     * @param taskId id assigned to this run, echoed into the result
     * @throws AnalysisCancelledException when the calling thread is interrupted mid-run
     */
    public AnalysisResult analyze(String taskId, AnalysisRequest request) {
        CleanedInput input = InputCleaner.clean(request);
        AnalysisParameters params = request.effectiveParameters();

        int backgroundSize = input.background().size();
        int querySize = input.query().size();

        List<CategoryResult> raw = new ArrayList<>();
        for (Map.Entry<String, Set<String>> category : input.annotations().entrySet()) {
            throwIfCancelled();

            Set<String> categoryGenes = category.getValue();
            int categorySize = categoryGenes.size();
            if (categorySize < params.minCategorySize() || categorySize > params.maxCategorySize()) {
                continue;
            }

            int intersection = 0;
            for (String gene : categoryGenes) {
                if (input.query().contains(gene)) {
                    intersection++;
                }
            }

            double expected = (double) querySize * categorySize / backgroundSize;
            EnrichmentDirection direction = intersection >= expected
                    ? EnrichmentDirection.OVER_REPRESENTED
                    : EnrichmentDirection.UNDER_REPRESENTED;
            double pValue = direction == EnrichmentDirection.OVER_REPRESENTED
                    ? Hypergeometric.upperTail(backgroundSize, categorySize, querySize, intersection)
                    : Hypergeometric.lowerTail(backgroundSize, categorySize, querySize, intersection);
            double fold = expected > 0.0 ? intersection / expected
                    : (intersection > 0 ? Double.POSITIVE_INFINITY : 0.0);

            raw.add(new CategoryResult(category.getKey(), categorySize, intersection, querySize,
                    backgroundSize, fold, pValue, 0.0, direction));
        }

        double[] pValues = raw.stream().mapToDouble(CategoryResult::pValue).toArray();
        double[] adjusted = PValueAdjuster.adjust(pValues, params.correctionMethod());

        List<CategoryResult> results = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            CategoryResult r = raw.get(i);
            results.add(new CategoryResult(r.categoryId(), r.categorySize(), r.intersectionSize(),
                    r.querySize(), r.backgroundSize(), r.foldEnrichment(), r.pValue(), adjusted[i],
                    r.direction()));
        }

        // Deterministic output order: adjusted p-value asc, then raw p-value,
        // then category id, so ties never depend on map iteration order.
        results.sort(Comparator.comparingDouble(CategoryResult::adjustedPValue)
                .thenComparingDouble(CategoryResult::pValue)
                .thenComparing(CategoryResult::categoryId));

        return new AnalysisResult(taskId, params, input.ignored(), List.copyOf(results));
    }

    private static void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            // Clear the flag: the worker thread may be reused by the pool for
            // another request, and the conversion to our own exception is the
            // cancellation handoff.
            Thread.interrupted();
            throw new AnalysisCancelledException("analysis was cancelled");
        }
    }
}
