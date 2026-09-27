package com.github.guoyyit.genesetenrichmentjava.store;

import com.github.guoyyit.genesetenrichmentjava.task.TaskRecord;

import java.util.List;
import java.util.Optional;

/**
 * Durable store for task records. Implementations must survive a restart.
 */
public interface ResultStore {

    void save(TaskRecord record);

    Optional<TaskRecord> find(String taskId);

    List<TaskRecord> findAll();
}
