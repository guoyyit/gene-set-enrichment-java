package com.github.guoyyit.genesetenrichmentjava.store;

import com.github.guoyyit.genesetenrichmentjava.model.JobRecord;

import java.util.List;
import java.util.Optional;

/**
 * Persistent storage for job records.
 */
public interface JobStore {

    void save(JobRecord record);

    Optional<JobRecord> find(String jobId);

    List<JobRecord> findAll();

    /**
     * Marks records left RUNNING/PENDING by an earlier process as FAILED so
     * restarted services never report stale in-progress jobs.
     */
    void recoverInterruptedJobs();
}
