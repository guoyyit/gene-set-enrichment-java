package com.github.guoyyit.genesetenrichmentjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.genesetenrichmentjava.task.TaskRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed {@link ResultStore}: one JSON file per task under a data
 * directory, written atomically (temp file + move).
 */
public class FileResultStore implements ResultStore {

    private final Path directory;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    public FileResultStore(Path directory, ObjectMapper objectMapper) {
        this.directory = directory;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create result store directory " + directory, e);
        }
    }

    @Override
    public void save(TaskRecord record) {
        Path target = fileFor(record.taskId());
        synchronized (writeLock) {
            try {
                Path temp = Files.createTempFile(directory, record.taskId(), ".tmp");
                objectMapper.writeValue(temp.toFile(), record);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to persist task " + record.taskId(), e);
            }
        }
    }

    @Override
    public Optional<TaskRecord> find(String taskId) {
        Path file = fileFor(taskId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    @Override
    public List<TaskRecord> findAll() {
        List<TaskRecord> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .forEach(f -> records.add(read(f)));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list stored tasks", e);
        }
        // Deterministic order: creation time, then id.
        records.sort(Comparator.comparing(TaskRecord::createdAt).thenComparing(TaskRecord::taskId));
        return records;
    }

    private TaskRecord read(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), TaskRecord.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read stored task from " + file, e);
        }
    }

    private Path fileFor(String taskId) {
        return directory.resolve(taskId + ".json");
    }
}
