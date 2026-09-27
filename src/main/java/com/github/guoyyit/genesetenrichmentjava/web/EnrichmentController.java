package com.github.guoyyit.genesetenrichmentjava.web;

import com.github.guoyyit.genesetenrichmentjava.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisResult;
import com.github.guoyyit.genesetenrichmentjava.model.JobRecord;
import com.github.guoyyit.genesetenrichmentjava.service.JobManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for gene set enrichment analysis jobs.
 */
@RestController
@RequestMapping("/api/v1/enrichment")
public class EnrichmentController {

    private final JobManager jobManager;

    public EnrichmentController(JobManager jobManager) {
        this.jobManager = jobManager;
    }

    @PostMapping("/jobs")
    public ResponseEntity<JobRecord> submit(@RequestBody AnalysisRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobManager.submit(request));
    }

    @GetMapping("/jobs")
    public List<JobRecord> list() {
        return jobManager.list();
    }

    @GetMapping("/jobs/{jobId}")
    public JobRecord get(@PathVariable String jobId) {
        return jobManager.get(jobId);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public JobRecord cancel(@PathVariable String jobId) {
        return jobManager.cancel(jobId);
    }

    @PostMapping("/analyze")
    public AnalysisResult analyze(@RequestBody AnalysisRequest request) {
        return jobManager.analyzeSynchronously(request);
    }
}
