package com.poseidon.codegraph.app.mcp;

import com.poseidon.codegraph.app.config.RepositoryConfigStore;
import com.poseidon.codegraph.app.config.RepositoryRequest;
import com.poseidon.codegraph.engine.application.model.CodeFunctionDO;
import com.poseidon.codegraph.engine.application.model.CodeRelationshipDO;
import com.poseidon.codegraph.storage.memory.repository.InMemoryCodeGraphRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "code-graph.tasks.enabled=false", "code-graph.mcp.token=test-only-token",
    "spring.datasource.url=jdbc:h2:mem:mcp-tests;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class GraphMcpIntegrationTest {
    @LocalServerPort int port;
    @Autowired RepositoryConfigStore projects;
    @Autowired InMemoryCodeGraphRepository graph;

    @Test void realClientInitializesDiscoversAndReadsOnlySelectedProject() {
        var project = projects.create(new RepositoryRequest("https://github.com/mcp-tests/demo.git", "main",
            List.of("java"), "NONE", null, null, null, List.of(), false));
        String scope = projects.identity(project.id()).graphScope();
        var function = new CodeFunctionDO();
        function.setId(scope + "::fn:save"); function.setName("save"); function.setProjectName(scope);
        function.setProjectFilePath("src/Service.java"); function.setLanguage("java");
        graph.insertFunctionsBatch(List.of(function));
        var edge = new CodeRelationshipDO();
        edge.setId("mcp-edge"); edge.setProjectName(scope); edge.setFromNodeId(function.getId());
        edge.setToNodeId(scope + "::fn:target"); edge.setRelationshipType("CALLS");
        graph.insertRelationshipsBatch(List.of(edge));
        var foreign = new CodeRelationshipDO();
        foreign.setId("mcp-foreign"); foreign.setProjectName("another-project");
        foreign.setFromNodeId(function.getId()); foreign.setToNodeId("private-target"); foreign.setRelationshipType("CALLS");
        graph.insertRelationshipsBatch(List.of(foreign));
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint("/mcp").openConnectionOnStartup(false)
            .customizeRequest(builder -> builder.header("Authorization", "Bearer test-only-token")).build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("code-graph");
            assertThat(client.listTools().tools()).extracting(Tool::name)
                .containsExactlyInAnyOrder("list_projects", "get_file_nodes", "trace_relationships");
            var listed = client.callTool(new CallToolRequest("list_projects", Map.of()));
            assertThat(text(listed)).contains(scope).doesNotContain("sshPrivateKey", "accessToken");
            var nodes = client.callTool(new CallToolRequest("get_file_nodes",
                Map.of("repositoryId", project.id(), "path", "src/Service.java")));
            assertThat(nodes.isError()).isFalse();
            assertThat(text(nodes)).contains(function.getId(), "save");
            var trace = client.callTool(new CallToolRequest("trace_relationships",
                Map.of("repositoryId", project.id(), "nodeId", function.getId(), "depth", 2)));
            assertThat(text(trace)).contains("mcp-edge").doesNotContain("private-target", "mcp-foreign");
            assertThat(client.callTool(new CallToolRequest("get_file_nodes",
                Map.of("repositoryId", project.id(), "path", "../secret"))).isError()).isTrue();
            assertThat(client.callTool(new CallToolRequest("trace_relationships",
                Map.of("repositoryId", project.id(), "nodeId", function.getId(), "depth", 99))).isError()).isTrue();
        }
        assertThat(graph.findAllRelationships()).hasSize(2); // Calls never modify the graph.
    }

    @Test void unauthenticatedAndForeignOriginAreRejectedAndInfoContainsNoToken() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
            assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            request.header("Authorization", "Bearer test-only-token").header("Origin", "https://attacker.example");
            assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            var info = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mcp")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(info.body()).contains("streamable-http").doesNotContain("test-only-token");
        }
    }
    private String text(CallToolResult result) { return ((TextContent) result.content().getFirst()).text(); }
}
