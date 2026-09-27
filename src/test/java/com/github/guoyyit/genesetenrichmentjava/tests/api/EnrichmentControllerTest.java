package com.github.guoyyit.genesetenrichmentjava.tests.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EnrichmentControllerTest {

    private static final Path DATA_DIR = createDataDir();

    private static Path createDataDir() {
        try {
            return Files.createTempDirectory("enrichment-web-test");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("enrichment.data-dir", DATA_DIR::toString);
        registry.add("enrichment.max-concurrent", () -> "2");
        registry.add("enrichment.max-queue", () -> "20");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private static final String VALID_BODY = """
            {
              "background": ["g1","g2","g3","g4","g5","g6","g7","g8","g9","g10"],
              "query": ["g1","g2","g3"],
              "annotations": {"catA": ["g1","g2","g3","g4"], "catB": ["g8","g9","g10"]},
              "parameters": {"correctionMethod": "BH", "minCategorySize": 1,
                             "maxCategorySize": 2147483647, "caseSensitive": false,
                             "timeoutMillis": 30000}
            }
            """;

    private String submitAndAwait(String body) throws Exception {
        MvcResult submitted = mvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = mapper.readTree(submitted.getResponse().getContentAsString())
                .get("taskId").asText();

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult polled = mvc.perform(get("/api/v1/analyses/" + taskId)).andReturn();
            JsonNode record = mapper.readTree(polled.getResponse().getContentAsString());
            String status = record.get("status").asText();
            if (!status.equals("PENDING") && !status.equals("RUNNING")) {
                return record.toString();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("task did not finish in time");
    }

    @Test
    void submitThenPollReturnsCompletedResult() throws Exception {
        JsonNode record = mapper.readTree(submitAndAwait(VALID_BODY));
        assertEquals("COMPLETED", record.get("status").asText());
        JsonNode categories = record.get("result").get("categories");
        assertEquals(2, categories.size());
        // BH alias resolved and echoed back
        assertEquals("BENJAMINI_HOCHBERG",
                record.get("result").get("parameters").get("correctionMethod").asText());
        // catA is the enriched one and sorts first
        assertEquals("catA", categories.get(0).get("categoryId").asText());
        assertEquals("OVER_REPRESENTED", categories.get(0).get("direction").asText());
    }

    @Test
    void sameRequestYieldsIdenticalResultsAcrossSubmissions() throws Exception {
        JsonNode first = mapper.readTree(submitAndAwait(VALID_BODY));
        JsonNode second = mapper.readTree(submitAndAwait(VALID_BODY));
        assertEquals(first.get("result").get("categories"), second.get("result").get("categories"));
        assertEquals(first.get("result").get("ignored"), second.get("result").get("ignored"));
    }

    @Test
    void invalidRequestIsRejectedSynchronously() throws Exception {
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"background\": [], \"query\": [\"a\"], \"annotations\": {\"x\": [\"a\"]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedBodyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unknownCorrectionMethodIsRejected() throws Exception {
        String body = VALID_BODY.replace("\"BH\"", "\"NOT_A_METHOD\"");
        mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unknownTaskReturns404() throws Exception {
        mvc.perform(get("/api/v1/analyses/no-such-task"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void cancelUnknownTaskReturns404() throws Exception {
        mvc.perform(post("/api/v1/analyses/no-such-task/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void cancelCompletedTaskReturns409() throws Exception {
        MvcResult submitted = mvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andReturn();
        String taskId = mapper.readTree(submitted.getResponse().getContentAsString())
                .get("taskId").asText();
        // wait for completion
        submitAndAwait(VALID_BODY);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String status = "";
        while (System.nanoTime() < deadline) {
            MvcResult polled = mvc.perform(get("/api/v1/analyses/" + taskId)).andReturn();
            status = mapper.readTree(polled.getResponse().getContentAsString()).get("status").asText();
            if (status.equals("COMPLETED")) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals("COMPLETED", status);
        mvc.perform(post("/api/v1/analyses/" + taskId + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_CANCELLABLE"));
    }

    @Test
    void listEndpointReturnsSubmittedTasks() throws Exception {
        submitAndAwait(VALID_BODY);
        MvcResult listed = mvc.perform(get("/api/v1/analyses")).andExpect(status().isOk()).andReturn();
        JsonNode tasks = mapper.readTree(listed.getResponse().getContentAsString());
        assertTrue(tasks.isArray());
        assertTrue(tasks.size() >= 1);
        assertTrue(tasks.get(0).has("status"));
    }
}
