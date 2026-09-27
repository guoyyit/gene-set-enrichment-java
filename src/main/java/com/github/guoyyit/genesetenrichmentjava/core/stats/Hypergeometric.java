package com.github.guoyyit.genesetenrichmentjava.core.stats;

/**
 * Hypergeometric tail probabilities, computed in log-space for numerical
 * stability with large populations. All methods are pure and thread-safe.
 */
public final class Hypergeometric {

    private Hypergeometric() {
    }

    /**
     * P(X >= k) where X ~ Hypergeometric(N population, K successes, n draws).
     */
    public static double upperTail(int population, int successes, int draws, int k) {
        validate(population, successes, draws);
        int lo = Math.max(k, Math.max(0, draws - (population - successes)));
        int hi = Math.min(successes, draws);
        if (lo > hi) {
            return 0.0;
        }
        return tailSum(population, successes, draws, lo, hi);
    }

    /**
     * P(X <= k) where X ~ Hypergeometric(N population, K successes, n draws).
     */
    public static double lowerTail(int population, int successes, int draws, int k) {
        validate(population, successes, draws);
        int lo = Math.max(0, draws - (population - successes));
        int hi = Math.min(k, Math.min(successes, draws));
        if (hi < lo) {
            return 0.0;
        }
        return tailSum(population, successes, draws, lo, hi);
    }

    private static double tailSum(int population, int successes, int draws, int lo, int hi) {
        double logDenominator = logChoose(population, draws);
        double[] logTerms = new double[hi - lo + 1];
        double maxLog = Double.NEGATIVE_INFINITY;
        for (int i = lo; i <= hi; i++) {
            double logTerm = logChoose(successes, i) + logChoose(population - successes, draws - i)
                    - logDenominator;
            logTerms[i - lo] = logTerm;
            if (logTerm > maxLog) {
                maxLog = logTerm;
            }
        }
        double sum = 0.0;
        for (double logTerm : logTerms) {
            sum += Math.exp(logTerm - maxLog);
        }
        double result = Math.exp(maxLog) * sum;
        // Clamp tiny floating point overshoot so results stay within [0, 1].
        return Math.min(1.0, Math.max(0.0, result));
    }

    private static void validate(int population, int successes, int draws) {
        if (population < 0 || successes < 0 || draws < 0 || successes > population || draws > population) {
            throw new IllegalArgumentException(
                    "Invalid hypergeometric parameters: N=" + population + ", K=" + successes + ", n=" + draws);
        }
    }

    static double logChoose(int n, int k) {
        if (k < 0 || k > n) {
            return Double.NEGATIVE_INFINITY;
        }
        return logGamma(n + 1) - logGamma(k + 1) - logGamma(n - k + 1);
    }

    /**
     * Lanczos approximation of ln(Gamma(x)) for x > 0.
     */
    static double logGamma(double x) {
        double[] coefficients = {
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
        if (x < 0.5) {
            // Reflection formula.
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1.0 - x);
        }
        x -= 1.0;
        double a = coefficients[0];
        for (int i = 1; i < coefficients.length; i++) {
            a += coefficients[i] / (x + i);
        }
        double t = x + 7.5;
        return 0.5 * Math.log(2.0 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }
}
