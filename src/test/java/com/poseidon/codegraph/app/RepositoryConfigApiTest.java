package com.poseidon.codegraph.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poseidon.codegraph.app.task.AnalysisTaskStore;
import com.poseidon.codegraph.app.task.AnalysisTaskEventStore;
import com.poseidon.codegraph.app.task.AnalysisWorkerIdentity;
import com.poseidon.codegraph.app.task.AnalysisWorkerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "code-graph.storage.type=memory",
    "code-graph.tasks.enabled=false",
    "code-graph.security.master-key=repository-api-test-key",
    "spring.datasource.url=jdbc:h2:mem:repository-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always"
})
class RepositoryConfigApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalysisTaskStore taskStore;

    @Autowired
    private AnalysisTaskEventStore taskEventStore;

    @Autowired
    private AnalysisWorkerStore workerStore;

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM analysis_task_event");
        jdbc.update("DELETE FROM analysis_task");
        jdbc.update("DELETE FROM repository_config");
    }

    @Test
    void repositoryConfigurationAndAnalysisTaskArePersisted() throws Exception {
        String createResponse = mockMvc.perform(multipart("/api/config/projects")
                .file(configPart(Map.of(
                    "gitRepoUrl", "https://github.com/example/payment-service.git",
                    "gitBranch", "main",
                    "languages", List.of("java"),
                    "authType", "ACCESS_TOKEN",
                    "accessToken", "plain-token",
                    "clearEndpointRules", false
                )))
                .file(rulesArchive(Map.of(
                    "rules/http.ser", "rule http { match GET build path /payments }",
                    "rules/redis.ser", "rule redis { match DEL build key payments }"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.name").value("example/payment-service"))
            .andExpect(jsonPath("$.data.languages[0]").value("java"))
            .andExpect(jsonPath("$.data.hasAccessToken").value(true))
            .andExpect(jsonPath("$.data.endpointRuleCount").value(2))
            .andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(createResponse).path("data");
        long repositoryId = created.path("id").asLong();
        String storedToken = jdbc.queryForObject(
            "SELECT access_token FROM repository_config WHERE id = ?", String.class, repositoryId);
        assertThat(storedToken).startsWith("enc:v1:").doesNotContain("plain-token");

        String taskResponse = mockMvc.perform(post("/api/config/projects/{id}/analyze", repositoryId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("QUEUED"))
            .andReturn().getResponse().getContentAsString();

        String taskId = objectMapper.readTree(taskResponse).path("data").path("id").asText();
        assertThat(taskStore.claimNext("api-test", Duration.ofSeconds(30))).isPresent();
        assertThat(taskStore.recoverExpiredLeases()).isZero();

        String cloneEventId = taskEventStore.start(taskId, "CLONE", "正在克隆仓库");
        taskEventStore.succeed(cloneEventId, "仓库克隆完成");

        mockMvc.perform(get("/api/tasks").param("repositoryId", Long.toString(repositoryId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].status").value("RUNNING"));

        mockMvc.perform(get("/api/tasks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].leaseOwner").value("api-test"));

        mockMvc.perform(get("/api/tasks/{taskId}/events", taskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].stage").value("QUEUED"))
            .andExpect(jsonPath("$.data[1].stage").value("CLONE"))
            .andExpect(jsonPath("$.data[1].status").value("SUCCEEDED"));

        workerStore.register(new AnalysisWorkerIdentity("api-worker"));
        workerStore.heartbeat("api-worker", taskId);
        mockMvc.perform(get("/api/workers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].workerId").value("api-worker"))
            .andExpect(jsonPath("$.data[0].activeTaskId").value(taskId));

        assertThat(taskStore.succeed(taskId, "api-test", 0)).isTrue();

        mockMvc.perform(multipart("/api/config/projects/{id}", repositoryId)
                .file(configPart(Map.of(
                    "gitRepoUrl", "https://github.com/example/payment-service.git",
                    "gitBranch", "release",
                    "languages", List.of("go"),
                    "authType", "NONE",
                    "clearEndpointRules", false
                )))
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hasAccessToken").value(false))
            .andExpect(jsonPath("$.data.status").value("done"))
            .andExpect(jsonPath("$.data.gitBranch").value("release"))
            .andExpect(jsonPath("$.data.endpointRuleCount").value(2));

        mockMvc.perform(multipart("/api/config/projects/{id}", repositoryId)
                .file(configPart(Map.of(
                    "gitRepoUrl", "https://github.com/example/payment-service.git",
                    "gitBranch", "release",
                    "languages", List.of("go"),
                    "authType", "NONE",
                    "clearEndpointRules", true
                )))
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                }))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.endpointRuleCount").value(0));

        assertThat(jdbc.queryForObject(
            "SELECT access_token FROM repository_config WHERE id = ?", String.class, repositoryId)).isNull();

        String canceledTaskId = taskStore.enqueue(repositoryId).id();
        mockMvc.perform(post("/api/tasks/{taskId}/cancel", canceledTaskId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("CANCELED"))
            .andExpect(jsonPath("$.data.cancelRequested").value(true));
    }

    @Test
    void repositoryRejectsMultipleLanguagesAndInvalidRuleArchive() throws Exception {
        mockMvc.perform(multipart("/api/config/projects")
                .file(configPart(Map.of(
                    "gitRepoUrl", "https://github.com/example/multi-language.git",
                    "languages", List.of("java", "go"),
                    "authType", "NONE"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("每个仓库只能选择一种源码语言"));

        mockMvc.perform(multipart("/api/config/projects")
                .file(configPart(Map.of(
                    "gitRepoUrl", "https://github.com/example/invalid-rules.git",
                    "languages", List.of("java"),
                    "authType", "NONE"
                )))
                .file(new MockMultipartFile(
                    "endpointRules", "rules.zip", "application/zip", "not-a-zip".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("端点规则包不是有效的 ZIP 文件"));
    }

    private MockMultipartFile configPart(Map<String, ?> config) throws Exception {
        return new MockMultipartFile(
            "config", "config.json", MediaType.APPLICATION_JSON_VALUE,
            objectMapper.writeValueAsBytes(config));
    }

    private MockMultipartFile rulesArchive(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("endpointRules", "endpoint-rules.zip", "application/zip", bytes.toByteArray());
    }
}
