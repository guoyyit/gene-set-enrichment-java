package com.github.guoyyit.genesetenrichmentjava.core.stats;

import com.github.guoyyit.genesetenrichmentjava.core.model.CorrectionMethod;

/**
 * Multiple-testing correction. Implementations are deterministic: equal
 * p-values always receive equal adjusted values and output order is stable.
 */
public final class PValueAdjuster {

    private PValueAdjuster() {
    }

    /**
     * Returns adjusted p-values aligned with the input order.
     */
    public static double[] adjust(double[] pValues, CorrectionMethod method) {
        return switch (method) {
            case BONFERRONI -> bonferroni(pValues);
            case BENJAMINI_HOCHBERG -> benjaminiHochberg(pValues, 1.0);
            case BENJAMINI_YEKUTIELI -> benjaminiHochberg(pValues, harmonic(pValues.length));
        };
    }

    private static double[] bonferroni(double[] pValues) {
        int m = pValues.length;
        double[] adjusted = new double[m];
        for (int i = 0; i < m; i++) {
            adjusted[i] = Math.min(1.0, pValues[i] * m);
        }
        return adjusted;
    }

    /**
     * Benjamini-Hochberg style step-up. factor is 1.0 for BH and the harmonic
     * number c(m) for Benjamini-Yekutieli.
     */
    private static double[] benjaminiHochberg(double[] pValues, double factor) {
        int m = pValues.length;
        // Stable sort of indices by ascending p-value; ties keep input order.
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator
                .comparingDouble((Integer i) -> pValues[i])
                .thenComparingInt(i -> i));

        double[] adjusted = new double[m];
        double runningMin = 1.0;
        for (int rank = m; rank >= 1; rank--) {
            int index = order[rank - 1];
            double candidate = pValues[index] * m * factor / rank;
            runningMin = Math.min(runningMin, candidate);
            adjusted[index] = Math.min(1.0, runningMin);
        }
        return adjusted;
    }

    private static double harmonic(int m) {
        double sum = 0.0;
        for (int i = 1; i <= m; i++) {
            sum += 1.0 / i;
        }
        return sum;
    }
}
