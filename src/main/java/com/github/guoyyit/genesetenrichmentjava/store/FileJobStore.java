package com.github.guoyyit.genesetenrichmentjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.genesetenrichmentjava.config.EnrichmentProperties;
import com.github.guoyyit.genesetenrichmentjava.model.JobRecord;
import com.github.guoyyit.genesetenrichmentjava.model.JobState;
import com.github.guoyyit.genesetenrichmentjava.service.AnalysisException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File-system backed {@link JobStore}: one JSON file per job under
 * {@code enrichment.store-dir}. Writes are atomic (temp file + move) and a
 * process-wide lock guards against interleaved writers.
 */
@Component
public class FileJobStore implements JobStore {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    /** Job record files are generated UUIDs; other JSON files are ignored. */
    private static final Pattern JOB_FILE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.json");

    private final EnrichmentProperties properties;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();
    private Path directory;

    public FileJobStore(EnrichmentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    private Path dir() {
        if (directory == null) {
            try {
                directory = Path.of(properties.getStoreDir());
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create job store directory: "
                        + properties.getStoreDir(), e);
            }
        }
        return directory;
    }

    private Path fileFor(String jobId) {
        if (jobId == null || !SAFE_ID.matcher(jobId).matches()) {
            throw new AnalysisException("INVALID_JOB_ID", "illegal job id: " + jobId);
        }
        return dir().resolve(jobId + ".json");
    }

    @Override
    public void save(JobRecord record) {
        Path target = fileFor(record.jobId());
        Path temp;
        synchronized (writeLock) {
            try {
                temp = Files.createTempFile(dir(), record.jobId(), ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), record);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("failed to persist job " + record.jobId(), e);
            }
        }
    }

    @Override
    public Optional<JobRecord> find(String jobId) {
        Path file = fileFor(jobId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), JobRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read job " + jobId, e);
        }
    }

    @Override
    public List<JobRecord> findAll() {
        List<JobRecord> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir())) {
            files.filter(f -> JOB_FILE.matcher(f.getFileName().toString()).matches())
                    .sorted()
                    .forEach(f -> {
                        try {
                            JobRecord record = objectMapper.readValue(f.toFile(), JobRecord.class);
                            if (record != null && record.jobId() != null) {
                                records.add(record);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(
                                    "failed to read job file " + f, e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list jobs", e);
        }
        records.sort(java.util.Comparator.comparing(JobRecord::jobId));
        return records;
    }

    @Override
    public void recoverInterruptedJobs() {
        Instant now = Instant.now();
        for (JobRecord record : findAll()) {
            if (record.state() == JobState.PENDING || record.state() == JobState.RUNNING) {
                JobRecord.JobError error = new JobRecord.JobError("INTERRUPTED_BY_RESTART",
                        "job was still in progress when the service restarted");
                save(record.withError(JobState.FAILED, error, now));
            }
        }
    }
}
