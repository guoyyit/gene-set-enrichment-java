package com.github.guoyyit.genesetenrichmentjava.api;

import com.github.guoyyit.genesetenrichmentjava.api.dto.SubmitResponse;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisParameters;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.task.TaskManager;
import com.github.guoyyit.genesetenrichmentjava.task.TaskRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for submitting and inspecting enrichment analyses.
 */
@RestController
@RequestMapping("/api/v1/analyses")
public class EnrichmentController {

    private final TaskManager taskManager;

    public EnrichmentController(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @PostMapping
    public SubmitResponse submit(@RequestBody AnalysisRequest request) {
        validate(request);
        String taskId = taskManager.submit(request);
        TaskRecord record = taskManager.find(taskId).orElseThrow();
        return new SubmitResponse(record.taskId(), record.status());
    }

    @GetMapping
    public List<TaskRecord> list() {
        return taskManager.list();
    }

    @GetMapping("/{taskId}")
    public TaskRecord get(@PathVariable String taskId) {
        return taskManager.find(taskId)
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "no task with id " + taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public TaskRecord cancel(@PathVariable String taskId) {
        taskManager.find(taskId)
                .orElseThrow(() -> new ApiException(ErrorCode.TASK_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "no task with id " + taskId));
        if (!taskManager.cancel(taskId)) {
            throw new ApiException(ErrorCode.TASK_NOT_CANCELLABLE, HttpStatus.CONFLICT,
                    "task " + taskId + " is already in a terminal state");
        }
        return taskManager.find(taskId).orElseThrow();
    }

    private static void validate(AnalysisRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "request body is required");
        }
        if (request.background() == null || request.background().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "background must be a non-empty list");
        }
        if (request.query() == null || request.query().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "query must be a non-empty list");
        }
        if (request.annotations() == null || request.annotations().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "annotations must be a non-empty map");
        }
        AnalysisParameters p = request.effectiveParameters();
        if (p.minCategorySize() < 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "minCategorySize must be >= 1");
        }
        if (p.maxCategorySize() < p.minCategorySize()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "maxCategorySize must be >= minCategorySize");
        }
        if (p.timeoutMillis() < 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST,
                    "timeoutMillis must be >= 0 (0 means no limit)");
        }
    }
}
