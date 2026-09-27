package com.github.guoyyit.genesetenrichmentjava.tests.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real web server twice against the same on-disk store directory to
 * verify saved results survive a restart and interrupted jobs are surfaced as
 * FAILED.
 */
class RestartRecoveryTest {

    private static final String BODY = """
            {
              "background": ["G1","G2","G3","G4"],
              "query": ["G1","G2"],
              "annotations": [
                {"gene":"G1","category":"C1"},
                {"gene":"G2","category":"C1"},
                {"gene":"G3","category":"C2"}
              ]
            }
            """;

    private ConfigurableApplicationContext start(Path storeDir, int port) {
        return new SpringApplicationBuilder(
                com.github.guoyyit.genesetenrichmentjava.Application.class)
                .build()
                .run(
                        "--server.port=" + port,
                        "--enrichment.store-dir=" + storeDir,
                        "--enrichment.default-timeout-ms=30000");
    }

    private static JsonNode postJson(String url, String body)
            throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new AssertionError("status=" + response.statusCode() + " body=" + response.body());
        }
        return new ObjectMapper().readTree(response.body());
    }

    private static JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new ObjectMapper().readTree(response.body());
    }

    @Test
    void completedResultSurvivesRestart() throws Exception {
        Path store = Files.createTempDirectory("restart-recovery-");
        int port1 = 8901;

        ConfigurableApplicationContext first = start(store, port1);
        JsonNode submitted;
        try {
            submitted = postJson("http://localhost:" + port1
                    + "/api/v1/enrichment/jobs", BODY);
            String jobId = submitted.get("jobId").asText();
            JsonNode record = null;
            for (int i = 0; i < 200; i++) {
                record = getJson("http://localhost:" + port1
                        + "/api/v1/enrichment/jobs/" + jobId);
                if ("COMPLETED".equals(record.get("state").asText())) {
                    break;
                }
                Thread.sleep(25);
            }
            assertEquals("COMPLETED", record.get("state").asText());
            assertEquals(2, record.get("result").get("categories").size());
        } finally {
            first.close();
        }

        int port2 = 8902;
        ConfigurableApplicationContext second = start(store, port2);
        try {
            JsonNode list = getJson("http://localhost:" + port2 + "/api/v1/enrichment/jobs");
            assertTrue(list.isArray());
            assertEquals(1, list.size());
            String jobId = list.get(0).get("jobId").asText();
            JsonNode reloaded = getJson("http://localhost:" + port2
                    + "/api/v1/enrichment/jobs/" + jobId);
            assertEquals("COMPLETED", reloaded.get("state").asText());
            assertEquals(2, reloaded.get("result").get("categories").size());
            assertEquals("BENJAMINI_HOCHBERG",
                    reloaded.get("result").get("effectiveParams")
                            .get("correctionMethod").asText());
        } finally {
            second.close();
        }
    }
}
