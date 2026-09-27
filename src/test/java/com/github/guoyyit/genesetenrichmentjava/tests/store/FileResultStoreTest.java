package com.github.guoyyit.genesetenrichmentjava.tests.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisParameters;
import com.github.guoyyit.genesetenrichmentjava.core.model.CorrectionMethod;
import com.github.guoyyit.genesetenrichmentjava.core.model.TaskStatus;
import com.github.guoyyit.genesetenrichmentjava.store.FileResultStore;
import com.github.guoyyit.genesetenrichmentjava.task.TaskRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileResultStoreTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private TaskRecord sample(String id, TaskStatus status, Instant created) {
        AnalysisParameters params = new AnalysisParameters(CorrectionMethod.BENJAMINI_YEKUTIELI,
                2, 500, true, 1234L);
        return new TaskRecord(id, status, params, null,
                status == TaskStatus.FAILED ? "INVALID_REQUEST" : null,
                status == TaskStatus.FAILED ? "bad input" : null, created, created.plusSeconds(1));
    }

    @Test
    void saveAndFindRoundTrips(@TempDir Path dir) {
        FileResultStore store = new FileResultStore(dir, mapper);
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        TaskRecord record = sample("task-1", TaskStatus.FAILED, now);
        store.save(record);

        TaskRecord loaded = store.find("task-1").orElseThrow();
        assertEquals(record, loaded);
        assertTrue(Files.exists(dir.resolve("task-1.json")));
        // atomic write leaves no temp files behind
        try (var stream = Files.list(dir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void dataSurvivesStoreRecreation(@TempDir Path dir) {
        Instant a = Instant.parse("2026-09-27T10:00:00Z");
        Instant b = Instant.parse("2026-09-27T11:00:00Z");
        new FileResultStore(dir, mapper).save(sample("task-a", TaskStatus.COMPLETED, a));
        new FileResultStore(dir, mapper).save(sample("task-b", TaskStatus.CANCELLED, b));

        // simulate restart: brand new store instance over the same directory
        FileResultStore restarted = new FileResultStore(dir, mapper);
        List<TaskRecord> all = restarted.findAll();
        assertEquals(2, all.size());
        assertEquals("task-a", all.get(0).taskId()); // deterministic order by createdAt
        assertEquals("task-b", all.get(1).taskId());
        assertEquals(TaskStatus.COMPLETED, all.get(0).status());
        assertEquals(CorrectionMethod.BENJAMINI_YEKUTIELI, all.get(0).parameters().correctionMethod());
    }

    @Test
    void overwriteReplacesPreviousSnapshot(@TempDir Path dir) {
        FileResultStore store = new FileResultStore(dir, mapper);
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        store.save(sample("task-1", TaskStatus.RUNNING, now));
        store.save(sample("task-1", TaskStatus.COMPLETED, now));
        assertEquals(TaskStatus.COMPLETED, store.find("task-1").orElseThrow().status());
        assertEquals(1, store.findAll().size());
    }

    @Test
    void missingTaskIsEmpty(@TempDir Path dir) {
        FileResultStore store = new FileResultStore(dir, mapper);
        assertTrue(store.find("ghost").isEmpty());
        assertFalse(store.find("ghost").isPresent());
    }
}
