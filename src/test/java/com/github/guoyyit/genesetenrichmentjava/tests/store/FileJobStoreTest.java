package com.github.guoyyit.genesetenrichmentjava.tests.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.genesetenrichmentjava.config.EnrichmentProperties;
import com.github.guoyyit.genesetenrichmentjava.model.JobRecord;
import com.github.guoyyit.genesetenrichmentjava.model.JobState;
import com.github.guoyyit.genesetenrichmentjava.store.FileJobStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileJobStoreTest {

    private FileJobStore newStore(Path dir) {
        EnrichmentProperties props = new EnrichmentProperties();
        props.setStoreDir(dir.toString());
        return new FileJobStore(props,
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    private JobRecord record(String id, JobState state) {
        Instant now = Instant.now();
        return new JobRecord(id, state, null, null, null, null, now, null, now);
    }

    private static Path uniqueDir() {
        return Path.of("target/store-test-" + System.nanoTime());
    }

    @Test
    void savesAndLoadsRecord() {
        Path dir = uniqueDir();
        FileJobStore store = newStore(dir);
        store.save(record("00000000-0000-0000-0000-000000000010", JobState.COMPLETED));
        assertEquals(JobState.COMPLETED, store.find("00000000-0000-0000-0000-000000000010").orElseThrow().state());
    }

    @Test
    void listingIsSortedByJobId() {
        Path dir = uniqueDir();
        FileJobStore store = newStore(dir);
        store.save(record("00000000-0000-0000-0000-000000000009", JobState.COMPLETED));
        store.save(record("00000000-0000-0000-0000-000000000001", JobState.FAILED));
        store.save(record("00000000-0000-0000-0000-000000000005", JobState.RUNNING));
        List<JobRecord> all = store.findAll();
        assertEquals(List.of("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000005", "00000000-0000-0000-0000-000000000009"),
                all.stream().map(JobRecord::jobId).toList());
    }

    @Test
    void recoveryMarksInterruptedJobsAsFailed() {
        Path dir = uniqueDir();
        FileJobStore first = newStore(dir);
        first.save(record("00000000-0000-0000-0000-0000000000a1", JobState.COMPLETED));
        first.save(record("00000000-0000-0000-0000-0000000000a2", JobState.RUNNING));
        first.save(record("00000000-0000-0000-0000-0000000000a3", JobState.PENDING));
        first.save(record("00000000-0000-0000-0000-0000000000a4", JobState.FAILED));

        // Simulate restart with a fresh store instance on the same directory.
        FileJobStore restarted = newStore(dir);
        restarted.recoverInterruptedJobs();

        assertEquals(JobState.COMPLETED, restarted.find("00000000-0000-0000-0000-0000000000a1").orElseThrow().state());
        assertEquals(JobState.FAILED, restarted.find("00000000-0000-0000-0000-0000000000a2").orElseThrow().state());
        assertEquals(JobState.FAILED, restarted.find("00000000-0000-0000-0000-0000000000a3").orElseThrow().state());
        assertEquals(JobState.FAILED, restarted.find("00000000-0000-0000-0000-0000000000a4").orElseThrow().state());
        assertEquals("INTERRUPTED_BY_RESTART",
                restarted.find("00000000-0000-0000-0000-0000000000a2").orElseThrow().error().code());
    }

    @Test
    void overwritingSaveReplacesFile() {
        Path dir = uniqueDir();
        FileJobStore store = newStore(dir);
        store.save(record("00000000-0000-0000-0000-0000000000b1", JobState.PENDING));
        store.save(record("00000000-0000-0000-0000-0000000000b1", JobState.COMPLETED));
        assertEquals(JobState.COMPLETED, store.find("00000000-0000-0000-0000-0000000000b1").orElseThrow().state());
        assertTrue(store.findAll().size() == 1);
    }

    @Test
    void ignoresForeignJsonFilesInStoreDirectory() throws Exception {
        Path dir = uniqueDir();
        FileJobStore store = newStore(dir);
        store.save(record("00000000-0000-0000-0000-000000000001", JobState.COMPLETED));
        Files.writeString(dir.resolve("payload.json"), "{\"not\":\"a job\"}");
        Files.writeString(dir.resolve("readme.txt"), "ignore me");
        Files.writeString(dir.resolve("00000000-0000-0000-0000-000000000002.tmp"), "temp");

        List<JobRecord> all = store.findAll();
        assertEquals(1, all.size());
        assertEquals("00000000-0000-0000-0000-000000000001", all.get(0).jobId());
        // recovery over the mixed directory must not fail
        store.recoverInterruptedJobs();
    }
}
