package com.github.guoyyit.genesetenrichmentjava.core.model;

/**
 * One input entry that was dropped during cleaning, with the reason.
 *
 * @param source  which part of the request it came from: BACKGROUND, QUERY or ANNOTATION
 * @param value   the raw value as submitted (may be blank)
 * @param reason  machine-readable reason code
 */
public record IgnoredEntry(String source, String value, Reason reason) {

    public enum Reason {
        BLANK,
        DUPLICATE,
        UNKNOWN_GENE,
        EMPTY_CATEGORY
    }
}
