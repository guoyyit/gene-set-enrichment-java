package com.github.guoyyit.genesetenrichmentjava.service;

/**
 * Hypergeometric distribution utilities used for category enrichment tests.
 *
 * <p>X ~ Hypergeometric(N, K, n): a population of N genes contains K genes of
 * a category; a query draws n genes without replacement and observes k hits.
 * Probabilities are computed in log space (Lanczos log-gamma) so large gene
 * universes do not overflow, and tails are summed with the max-term factored
 * out for numerical stability.
 */
public final class Hypergeometric {

    private Hypergeometric() {
    }

    // Lanczos approximation coefficients (g = 7, n = 9 to 15 digits).
    private static final double[] LANCZOS = {
            0.99999999999980993,
            676.5203681218851,
            -1259.1392167224028,
            771.32342877765313,
            -176.61502916214059,
            12.507343278686905,
            -0.13857109526572012,
            9.9843695780195716e-6,
            1.5056327351493116e-7
    };

    private static double logGamma(double x) {
        if (x < 0.5) {
            // Reflection formula.
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1.0 - x);
        }
        x -= 1.0;
        double a = LANCZOS[0];
        double t = x + 7.5;
        for (int i = 1; i < LANCZOS.length; i++) {
            a += LANCZOS[i] / (x + i);
        }
        return 0.5 * Math.log(2.0 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    private static double logChoose(int n, int k) {
        if (k < 0 || k > n) {
            return Double.NEGATIVE_INFINITY;
        }
        return logGamma(n + 1.0) - logGamma(k + 1.0) - logGamma(n - k + 1.0);
    }

    private static double logPmf(int populationSize, int successCount, int drawCount, int k) {
        return logChoose(successCount, k)
                + logChoose(populationSize - successCount, drawCount - k)
                - logChoose(populationSize, drawCount);
    }

    /**
     * Sums exp of log values over [from, to] of logPmf with max-term factoring.
     */
    private static double sumTail(int populationSize, int successCount, int drawCount,
                                  int from, int to) {
        if (from > to) {
            return 0.0;
        }
        double maxLog = Double.NEGATIVE_INFINITY;
        double[] logs = new double[to - from + 1];
        for (int k = from; k <= to; k++) {
            double lp = logPmf(populationSize, successCount, drawCount, k);
            logs[k - from] = lp;
            if (lp > maxLog) {
                maxLog = lp;
            }
        }
        if (Double.isInfinite(maxLog)) {
            return 0.0;
        }
        double sum = 0.0;
        for (double lp : logs) {
            sum += Math.exp(lp - maxLog);
        }
        double result = Math.exp(maxLog) * sum;
        // Guard against tiny floating point overshoot.
        return Math.min(1.0, Math.max(0.0, result));
    }

    private static void validate(int populationSize, int successCount,
                                 int drawCount, int observed) {
        if (populationSize <= 0 || successCount < 0 || successCount > populationSize
                || drawCount < 0 || drawCount > populationSize
                || observed < 0 || observed > drawCount) {
            throw new AnalysisException("INVALID_INPUT",
                    "invalid hypergeometric parameters: N=" + populationSize
                            + ", K=" + successCount + ", n=" + drawCount + ", k=" + observed);
        }
    }

    /**
     * P(X &gt;= observed), X ~ Hypergeometric(N, K, n).
     */
    public static double upperTailPValue(int populationSize, int successCount,
                                         int drawCount, int observed) {
        validate(populationSize, successCount, drawCount, observed);
        int lo = Math.max(0, drawCount - (populationSize - successCount));
        int hi = Math.min(successCount, drawCount);
        int from = Math.max(observed, lo);
        return sumTail(populationSize, successCount, drawCount, from, hi);
    }

    /**
     * P(X &lt;= observed), X ~ Hypergeometric(N, K, n).
     */
    public static double lowerTailPValue(int populationSize, int successCount,
                                         int drawCount, int observed) {
        validate(populationSize, successCount, drawCount, observed);
        int lo = Math.max(0, drawCount - (populationSize - successCount));
        int hi = Math.min(successCount, drawCount);
        int to = Math.min(observed, hi);
        return sumTail(populationSize, successCount, drawCount, lo, to);
    }

    /**
     * Fold enrichment: (k/n) / (K/N). Returns 0 when k == 0.
     */
    public static double foldEnrichment(int populationSize, int successCount,
                                        int drawCount, int observed) {
        validate(populationSize, successCount, drawCount, observed);
        if (successCount == 0 || drawCount == 0) {
            return 0.0;
        }
        return ((double) observed / drawCount) / ((double) successCount / populationSize);
    }
}
