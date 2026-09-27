package com.github.guoyyit.genesetenrichmentjava.tests.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EnrichmentControllerTest {

    @TempDir
    static Path tempStore;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("enrichment.store-dir", () -> tempStore.toString());
        registry.add("enrichment.default-timeout-ms", () -> "30000");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String VALID_BODY = """
            {
              "background": ["G1","G2","G3","G4","G5","G6"],
              "query": ["G1","G2","G3"],
              "annotations": [
                {"gene":"G1","category":"CAT1"},
                {"gene":"G2","category":"CAT1"},
                {"gene":"G4","category":"CAT2"}
              ],
              "params": {"correctionMethod":"BENJAMINI_HOCHBERG"}
            }
            """;

    @Test
    void synchronousAnalyzeReturnsResult() throws Exception {
        mvc.perform(post("/api/v1/enrichment/analyze")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundSize").value(6))
                .andExpect(jsonPath("$.querySize").value(3))
                .andExpect(jsonPath("$.categoryCount").value(2))
                .andExpect(jsonPath("$.effectiveParams.correctionMethod")
                        .value("BENJAMINI_HOCHBERG"))
                .andExpect(jsonPath("$.categories[0].category").exists())
                .andExpect(jsonPath("$.categories[0].pValue").exists())
                .andExpect(jsonPath("$.categories[0].adjustedPValue").exists());
    }

    @Test
    void asyncJobLifecycleWorks() throws Exception {
        MvcResult submitted = mvc.perform(post("/api/v1/enrichment/jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andReturn();
        String jobId = objectMapper.readTree(submitted.getResponse().getContentAsString())
                .get("jobId").asText();

        // Poll until completed.
        JsonNode record = null;
        for (int i = 0; i < 200; i++) {
            MvcResult got = mvc.perform(get("/api/v1/enrichment/jobs/" + jobId))
                    .andExpect(status().isOk()).andReturn();
            record = objectMapper.readTree(got.getResponse().getContentAsString());
            if ("COMPLETED".equals(record.get("state").asText())) {
                break;
            }
            Thread.sleep(25);
        }
        assertNotNull(record);
        assertEquals("COMPLETED", record.get("state").asText());
        assertTrue(record.get("result").get("categories").size() == 2);

        // Listing contains the job.
        mvc.perform(get("/api/v1/enrichment/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").exists());
    }

    @Test
    void unknownJobReturns404WithCode() throws Exception {
        mvc.perform(get("/api/v1/enrichment/jobs/no-such-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void invalidRequestReturns400WithCode() throws Exception {
        String body = """
                {"background": ["G1"], "query": ["ZZ"], "annotations": []}
                """;
        mvc.perform(post("/api/v1/enrichment/analyze")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/v1/enrichment/analyze")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        String body = """
                {"background": ["G1","G2"], "query": ["G1"],
                 "annotations": [], "params": {"minCategorySize": 5, "maxCategorySize": 2}}
                """;
        mvc.perform(post("/api/v1/enrichment/analyze")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMS"));
    }
}
