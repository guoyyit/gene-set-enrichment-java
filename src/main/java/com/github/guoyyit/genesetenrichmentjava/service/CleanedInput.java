package com.github.guoyyit.genesetenrichmentjava.service;

import com.github.guoyyit.genesetenrichmentjava.model.IgnoredInputReport;

import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Result of input cleaning: canonical gene sets and category->genes mapping,
 * together with the report of ignored/normalized entries.
 */
public record CleanedInput(
        LinkedHashSet<String> background,
        LinkedHashSet<String> query,
        TreeMap<String, TreeSet<String>> annotations,
        IgnoredInputReport report) {
}
