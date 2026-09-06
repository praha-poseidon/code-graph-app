package com.poseidon.codegraph.app;

import com.poseidon.codegraph.engine.application.repository.CodeRelationshipRepository;
import com.poseidon.codegraph.model.RelationshipType;
import com.poseidon.codegraph.model.delta.ParseRequest;
import com.poseidon.codegraph.model.event.ChangeType;
import com.poseidon.codegraph.parser.process.ProcessCodeGraphParser;
import com.poseidon.codegraph.starter.service.IncrementalUpdateService;
import com.poseidon.codegraph.storage.memory.repository.InMemoryCodeGraphRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "PYTHON_CODE_GRAPH_CLI", matches = ".+")
class PythonProcessParserEndToEndTest {

    private static final String PROJECT = "python-process-e2e";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void configureExternalParser() {
        System.setProperty("codegraph.parser.process.languages", "python");
        System.setProperty("codegraph.parser.process.python.command",
            "python3 '" + System.getenv("PYTHON_CODE_GRAPH_CLI") + "' --stdio");
        System.setProperty("codegraph.parser.process.timeoutSeconds", "30");
    }

    @AfterAll
    static void clearExternalParserConfig() {
        System.clearProperty("codegraph.parser.process.languages");
        System.clearProperty("codegraph.parser.process.python.command");
        System.clearProperty("codegraph.parser.process.timeoutSeconds");
    }

    @Test
    void appServiceWritesPythonGraphFromExternalProcessParser() {
        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        IncrementalUpdateService service = new IncrementalUpdateService(
            repository, repository, repository, repository, repository
        );
        Path projectRoot = pythonParserRoot().resolve("fixtures/basic");
        Path sourceFile = projectRoot.resolve("app.py");

        service.handleFileAdded(
            PROJECT,
            sourceFile.toString(),
            "app.py",
            "git@example/python-process-e2e.git",
            "main",
            new String[0],
            new String[] { projectRoot.toString() }
        );

        String runId = PROJECT + "::fn:app.run()";
        String helperId = PROJECT + "::fn:app.helper()";
        assertThat(repository.findFunctionsByProjectFilePath(PROJECT, "app.py"))
            .extracting(function -> function.getId())
            .contains(runId, helperId);
        assertThat(repository.findOutgoingRelationships(PROJECT, runId, RelationshipType.CALLS.name()))
            .extracting(relationship -> relationship.getToNodeId())
            .contains(helperId);
    }

    @Test
    void processProtocolAcceptsPythonEndpoints() {
        Path projectRoot = pythonParserRoot().resolve("fixtures/basic");
        Path sourceFile = projectRoot.resolve("app.py");
        ProcessCodeGraphParser parser = new ProcessCodeGraphParser(
            "python",
            List.of("python3", System.getenv("PYTHON_CODE_GRAPH_CLI"), "--stdio"),
            Duration.ofSeconds(30)
        );

        var delta = parser.parse(new ParseRequest(
            PROJECT,
            "python",
            projectRoot.toString(),
            List.of(sourceFile.toString()),
            List.of(projectRoot.toString()),
            List.of(),
            "git@example/python-process-e2e.git",
            "main",
            ChangeType.SOURCE_ADDED,
            List.of(),
            List.of(),
            Map.of(),
            Map.of("staticExtractPresetRules", true)
        ));

        assertThat(delta.endpoints())
            .extracting(endpoint -> endpoint.getMatchIdentity())
            .contains("HTTP:GET:/api/run");
        assertThat(delta.relationships())
            .extracting(relationship -> relationship.getRelationshipType())
            .contains(RelationshipType.ENDPOINT_TO_FUNCTION);
    }

    /**
     * Python oracle: inheritance, Protocol conformance and method overriding are distinct
     * semantic facts. A same-named unrelated class must not acquire an override edge.
     */
    @Test
    void pythonNativeSemanticOracleSurvivesIncrementalPersistence() throws Exception {
        Path root = createSemanticFixture("python-semantics");
        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        IncrementalUpdateService service = new IncrementalUpdateService(
            repository, repository, repository, repository, repository);
        applySemanticFixture(service, root);
        assertPythonSemanticOracle(repository);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEO4J_URI", matches = ".+")
    void pythonNativeSemanticOracleMatchesPersistedNeo4jEdges() throws Exception {
        Path root = createSemanticFixture("python-semantics-neo4j");
        try (Neo4jContractGraph graph = Neo4jContractGraph.open(PROJECT)) {
            applySemanticFixture(graph.service(), root);
            assertPythonSemanticOracle(graph.relationships());
        }
    }

    private Path createSemanticFixture(String name) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(root.resolve("contracts.py"), """
            from typing import Protocol

            class Gateway(Protocol):
                def send(self, value: str) -> str: ...

            class Base:
                def run(self) -> None: pass
            """);
        Files.writeString(root.resolve("service.py"), """
            from contracts import Base, Gateway

            class Service(Base):
                def run(self) -> None: pass

            class GatewayService(Gateway):
                def send(self, value: str) -> str: return value

            class Unrelated:
                def send(self, value: str) -> str: return value
            """);
        return root;
    }

    private void applySemanticFixture(IncrementalUpdateService service, Path root) {
        try (var session = service.openSession("python")) {
            for (String relative : List.of("contracts.py", "service.py")) {
                session.handleFileAdded(
                    PROJECT,
                    root.resolve(relative).toString(),
                    relative,
                    "git@example/python-semantics.git",
                    "main",
                    new String[0],
                    new String[] {root.toString()},
                    List.of(),
                    List.of());
            }
        }
    }

    private void assertPythonSemanticOracle(CodeRelationshipRepository repository) {
        assertEdge(repository, "unit:service.Service", "INHERITS", "unit:contracts.Base");
        assertEdge(repository, "unit:service.GatewayService", "CONFORMS", "unit:contracts.Gateway");
        assertEdge(repository, "fn:service.Service::run()", "OVERRIDES", "fn:contracts.Base::run()");
        assertEdge(repository, "fn:service.GatewayService::send()", "OVERRIDES", "fn:contracts.Gateway::send()");
        assertNoEdge(repository, "fn:service.Unrelated::send()", "OVERRIDES", "fn:contracts.Gateway::send()");
    }

    private void assertEdge(CodeRelationshipRepository repository, String from, String type, String to) {
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped(from), type))
            .as("Python persisted edge %s -[%s]-> %s", from, type, to)
            .extracting(relationship -> relationship.getToNodeId())
            .contains(scoped(to));
    }

    private void assertNoEdge(CodeRelationshipRepository repository, String from, String type, String to) {
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped(from), type))
            .as("forbidden Python edge %s -[%s]-> %s", from, type, to)
            .extracting(relationship -> relationship.getToNodeId())
            .doesNotContain(scoped(to));
    }

    private String scoped(String id) {
        return PROJECT + "::" + id;
    }

    private static Path pythonParserRoot() {
        Path cli = Path.of(System.getenv("PYTHON_CODE_GRAPH_CLI")).toAbsolutePath();
        return cli.getParent().getParent();
    }
}
