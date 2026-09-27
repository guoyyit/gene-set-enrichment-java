# Gene Set Enrichment Service (Java / Spring Boot)

A locally reproducible **over-representation analysis (ORA)** service. A caller
supplies a background gene set, a query (gene list of interest) and
gene-to-category annotations; the service returns per-category hypergeometric
enrichment statistics with multiple-testing correction. Results are
deterministic for identical input/parameters, requests are isolated, long
tasks support timeout and cancellation, and job history survives restarts.

## 1. Build and run

```bash
mvn spring-boot:run            # starts on http://localhost:8080
mvn test                       # run all automated tests (43 tests)
mvn package && java -jar target/gene-set-enrichment-java-1.0-SNAPSHOT.jar
```

## 2. API

Base path: `/api/v1/enrichment`

| Method | Path | Purpose |
|---|---|---|
| POST | `/analyze` | Run an analysis synchronously, return the result |
| POST | `/jobs` | Submit an asynchronous job (`202 Accepted`, returns the job record) |
| GET | `/jobs` | List all saved jobs, sorted by job id |
| GET | `/jobs/{jobId}` | Fetch one job (state + result/error) |
| POST | `/jobs/{jobId}/cancel` | Cancel an in-progress job |

### Request body

```json
{
  "background": ["BRCA1", "TP53", "EGFR", "KRAS"],
  "query":      ["brca1", "tp53", "UNKNOWN"],
  "annotations": [
    {"gene": "BRCA1", "category": "DNA_REPAIR"},
    {"gene": "TP53",  "category": "DNA_REPAIR"},
    {"gene": "EGFR",  "category": "SIGNALING"}
  ],
  "params": {
    "correctionMethod": "BENJAMINI_HOCHBERG",
    "minCategorySize": 1,
    "maxCategorySize": 2147483647,
    "caseSensitive": false,
    "timeoutMs": 30000
  }
}
```

All `params` fields are optional; the **effective** parameter values are always
echoed back in the response (`effectiveParams`) so the computational recipe is
traceable per result. Defaults:

| Parameter | Default | Meaning |
|---|---|---|
| `correctionMethod` | `BENJAMINI_HOCHBERG` | Multiple-testing correction (see §4) |
| `minCategorySize` | `1` | Categories with fewer background genes are dropped and reported |
| `maxCategorySize` | `2147483647` | Categories with more background genes are dropped and reported |
| `caseSensitive` | `false` | When `false`, gene symbols are trimmed and upper-cased before matching |
| `timeoutMs` | server default `30000` | Per-analysis wall-clock budget |

### Response (`AnalysisResult`)

```json
{
  "effectiveParams": { "correctionMethod": "BENJAMINI_HOCHBERG", "...": "..." },
  "ignored": {
    "blankBackgroundEntries": [],
    "blankQueryEntries": [],
    "duplicateBackgroundGenes": ["BRCA1"],
    "duplicateQueryGenes": [],
    "caseNormalizedBackgroundGenes": ["brca1"],
    "caseNormalizedQueryGenes": ["brca1", "tp53"],
    "unknownQueryGenes": ["UNKNOWN"],
    "blankAnnotationEntries": [],
    "caseNormalizedAnnotationGenes": [],
    "unknownAnnotationGenes": [],
    "duplicateAnnotations": [],
    "sizeFilteredCategories": []
  },
  "backgroundSize": 4,
  "querySize": 2,
  "categoryCount": 2,
  "categories": [
    {
      "category": "DNA_REPAIR",
      "backgroundSize": 4,
      "querySize": 2,
      "categorySize": 2,
      "intersectionSize": 2,
      "foldEnrichment": 2.0,
      "direction": "OVER_REPRESENTED",
      "pValue": 0.5,
      "adjustedPValue": 0.5
    }
  ],
  "computedAt": "2026-09-27T10:15:30Z",
  "durationMs": 3
}
```

## 3. Input cleaning rules (requirement 1)

Every ignored/normalized input entry is reported explicitly under `ignored`
(all lists sorted, so the report itself is deterministic):

1. **Blank entries** – `null` or whitespace-only genes/annotations are dropped
   and reported (`<null>` / `<blank len=n>` markers for gene lists).
2. **Trimming** – surrounding whitespace is removed.
3. **Case** – by default symbols are normalized to upper case; every raw form
   that changed case is listed in `caseNormalized*Genes`. Set
   `"caseSensitive": true` to keep symbols case-sensitive.
4. **Duplicates** – repeated genes within a list, or repeated
   gene-category annotation pairs, are collapsed and reported.
5. **Unknown genes** – query genes absent from the background, and annotation
   genes absent from the background, are dropped and reported
   (`unknownQueryGenes`, `unknownAnnotationGenes`).
6. **Category size filter** – categories outside
   `[minCategorySize, maxCategorySize]` (counted against the background) are
   dropped and reported in `sizeFilteredCategories` with their size.

A request whose background or query has no usable gene after cleaning is
rejected with `INVALID_INPUT`.

## 4. Statistical definitions (requirements 2)

For a category, with

* `N` = background size,
* `K` = number of background genes annotated to the category,
* `n` = query size,
* `k` = number of query genes annotated to the category (intersection),

let `X ~ Hypergeometric(N, K, n)`:

* **Fold enrichment** = observed hit fraction divided by expected hit fraction
  under the null: `(k / n) / (K / N)`. It is `0` when `k = 0`.
* **Direction**:
  * `OVER_REPRESENTED` when `k >= n*K/N`; p-value = upper tail `P(X >= k)`;
  * `UNDER_REPRESENTED` when `k <  n*K/N`; p-value = lower tail `P(X <= k)`.

Hypergeometric probabilities are evaluated in log space with a Lanczos
log-gamma approximation, so large backgrounds (tested up to `N = 50000`) do
not overflow; tail sums use max-term factoring for numerical stability. Raw
p-values and reference values from exact integer combinatorics agree to
~1e-12 on small inputs.

### Multiple-testing correction (requirement 3)

The correction scope is exactly the set of categories returned after size
filtering (`m = categoryCount`). Supported methods (all deterministic pure
functions of the p-value vector; ties broken by stable category/index order):

* `NONE` – raw p-values.
* `BONFERRONI` – `min(1, p_i · m)`.
* `HOLM` – Holm step-down: sorted `p_(1)…p_(m)`,
  `adj_(i) = max_{j<=i} min(1, (m-j+1)·p_(j))`.
* `BENJAMINI_HOCHBERG` – BH step-up:
  `adj_(i) = min_{j>=i} min(1, m/j · p_(j))`.
* `BENJAMINI_YEKUTIELI` – BH with the arbitrary-dependence constant
  `c(m) = Σ 1/i`, i.e. `min(1, c(m)·m/j·p_(j))`.

The method actually used is recorded in `effectiveParams.correctionMethod`,
making every adjusted value reproducible and traceable.

## 5. Determinism and isolation (requirement 4)

* Categories are sorted by `(pValue, adjustedPValue, category name)`; report
  lists are sorted; identifiers/values are deterministic functions of input.
* Repeating the same request yields identical ordering and floating-point
  values (covered by repeated-run tests).
* Each analysis builds its own local data structures; there is no mutable
  shared analysis state between requests.

## 6. Timeout, cancellation, concurrency (requirements 5–6)

* A bounded `ThreadPoolTaskExecutor` runs async jobs
  (`enrichment.executor.core-size/max-size/queue-capacity`). When pool and
  queue are full, the job is **rejected fast** with `SERVICE_BUSY`
  (HTTP 503) instead of blocking web threads.
* Every job has its own cancellation token; the analysis checks it
  periodically. `POST /jobs/{id}/cancel` marks the job `CANCELLED`, interrupts
  the worker, and **no partial result is ever returned**.
* Exceeding `timeoutMs` marks the job `FAILED` with error code `JOB_TIMEOUT`;
  again no partial result is stored.
* A failed job only affects itself; subsequent jobs run normally.

Configuration (`application.properties`):

```properties
enrichment.default-timeout-ms=30000
enrichment.store-dir=data/jobs
enrichment.executor.core-size=4
enrichment.executor.max-size=8
enrichment.executor.queue-capacity=100
```

## 7. Persistence and restart recovery (requirement 7)

Each job is stored as one JSON file under `enrichment.store-dir`
(`{jobId}.json`, atomic temp-file + move). States:
`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`.

On startup, any record left `PENDING`/`RUNNING` by a previous process (i.e.
the process died mid-job) is rewritten to `FAILED` with code
`INTERRUPTED_BY_RESTART`, so stale "in progress" jobs are never reported.
Completed jobs, their parameters and results remain queryable after restart.

## 8. Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_INPUT` | 400 | Missing/empty/blank/unknown-only genes; malformed JSON |
| `INVALID_PARAMS` | 400 | Bad size bounds, non-positive timeout, unknown enum value |
| `JOB_NOT_FOUND` | 404 | Unknown job id |
| `INVALID_JOB_ID` | 404 | Malformed job id |
| `CANCELLED` | 409 | Synchronous execution was cancelled/interrupted |
| `JOB_TIMEOUT` | 408 | Analysis exceeded `timeoutMs` |
| `SERVICE_BUSY` | 503 | Bounded pool + queue are full |
| `INTERRUPTED_BY_RESTART` | (job record) | Job was in progress when the service restarted |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

Error body: `{"code": "...", "message": "...", "timestamp": "..."}`.

## 9. Tests

`mvn test` covers: input cleaning, hypergeometric values against exact
references, all four correction strategies, deterministic repeated runs,
cancellation mid-analysis, timeout, bounded-executor rejection, concurrent
submission/result equality, failed-job isolation, and full-server restart
recovery.
