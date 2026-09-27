package com.github.guoyyit.genesetenrichmentjava.service;

import com.github.guoyyit.genesetenrichmentjava.model.CorrectionMethod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Multiple testing correction. Adjusted values are returned in the same
 * order as the input so callers can map them back by index.
 *
 * <p>Supported strategies:
 * <ul>
 *   <li>{@code NONE}: pass-through;</li>
 *   <li>{@code BONFERRONI}: min(1, p * m);</li>
 *   <li>{@code HOLM}: step-down, adj(i) = max over j&lt;=i of min(1, (m-j) * p(j));</li>
 *   <li>{@code BENJAMINI_HOCHBERG}: step-up, adj(i) = min over j&gt;=i of min(1, m/j * p(j));</li>
 *   <li>{@code BENJAMINI_YEKUTIELI}: BH scaled by c(m) = sum(1/i).</li>
 * </ul>
 * All strategies are deterministic pure functions of the input vector.
 */
public final class PValueAdjuster {

    private PValueAdjuster() {
    }

    public static List<Double> adjust(List<Double> pValues, CorrectionMethod method) {
        if (pValues == null || pValues.isEmpty()) {
            return List.of();
        }
        CorrectionMethod effective = method == null ? CorrectionMethod.NONE : method;
        int m = pValues.size();
        double[] p = new double[m];
        for (int i = 0; i < m; i++) {
            Double v = pValues.get(i);
            double value = v == null || Double.isNaN(v) ? 1.0 : v;
            p[i] = Math.min(1.0, Math.max(0.0, value));
        }

        double[] adjusted = switch (effective) {
            case NONE -> p;
            case BONFERRONI -> bonferroni(p, m);
            case HOLM -> holm(p, m);
            case BENJAMINI_HOCHBERG -> benjaminiHochberg(p, m, 1.0);
            case BENJAMINI_YEKUTIELI -> benjaminiHochberg(p, m, harmonic(m));
        };

        List<Double> result = new ArrayList<>(m);
        for (double v : adjusted) {
            result.add(Math.min(1.0, Math.max(0.0, v)));
        }
        return result;
    }

    private static double harmonic(int m) {
        double c = 0.0;
        for (int i = 1; i <= m; i++) {
            c += 1.0 / i;
        }
        return c;
    }

    /**
     * Indices sorted by p-value ascending; ties broken by index for
     * determinism.
     */
    private static Integer[] sortedIndices(double[] p) {
        Integer[] order = new Integer[p.length];
        for (int i = 0; i < p.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int cmp = Double.compare(p[a], p[b]);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        return order;
    }

    private static double[] bonferroni(double[] p, int m) {
        double[] out = new double[m];
        for (int i = 0; i < m; i++) {
            out[i] = Math.min(1.0, p[i] * m);
        }
        return out;
    }

    private static double[] holm(double[] p, int m) {
        Integer[] order = sortedIndices(p);
        double[] out = new double[m];
        double runningMax = 0.0;
        for (int rank = 0; rank < m; rank++) {
            double candidate = (m - rank) * p[order[rank]];
            runningMax = Math.max(runningMax, candidate);
            out[order[rank]] = Math.min(1.0, runningMax);
        }
        return out;
    }

    private static double[] benjaminiHochberg(double[] p, int m, double scale) {
        Integer[] order = sortedIndices(p);
        double[] out = new double[m];
        double runningMin = 1.0;
        for (int rank = m - 1; rank >= 0; rank--) {
            double candidate = scale * p[order[rank]] * m / (rank + 1);
            runningMin = Math.min(runningMin, candidate);
            out[order[rank]] = Math.min(1.0, runningMin);
        }
        return out;
    }
}
