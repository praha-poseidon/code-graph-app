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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "PHP_CODE_GRAPH_CLI", matches = ".+")
class PhpProcessParserEndToEndTest {

    private static final String PROJECT = "php-process-e2e";

    @BeforeAll
    static void configureExternalParser() {
        System.setProperty("codegraph.parser.process.languages", "php");
        System.setProperty("codegraph.parser.process.php.command",
            "'" + System.getenv("PHP_CODE_GRAPH_CLI") + "' --stdio");
        System.setProperty("codegraph.parser.process.timeoutSeconds", "30");
    }

    @AfterAll
    static void clearExternalParserConfig() {
        System.clearProperty("codegraph.parser.process.languages");
        System.clearProperty("codegraph.parser.process.php.command");
        System.clearProperty("codegraph.parser.process.timeoutSeconds");
    }

    @Test
    void appServiceWritesPhpGraphFromExternalProcessParser() {
        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        IncrementalUpdateService service = new IncrementalUpdateService(
            repository,
            repository,
            repository,
            repository,
            repository
        );
        Path projectRoot = phpParserRoot().resolve("fixtures/basic");
        Path sourceFile = projectRoot.resolve("src/App.php");

        service.handleFileAdded(
            PROJECT,
            sourceFile.toString(),
            "src/App.php",
            "git@example/php-process-e2e.git",
            "main",
            new String[0],
            new String[] { projectRoot.resolve("src").toString() }
        );

        String packageId = PROJECT + "::pkg:App";
        String helperId = PROJECT + "::fn:App\\helper()";
        String runId = PROJECT + "::fn:App\\Service::run()";

        assertThat(repository.findOutgoingRelationships(
                PROJECT, packageId, RelationshipType.PACKAGE_TO_UNIT.name()))
            .extracting(relationship -> relationship.getToNodeId())
            .contains(
                PROJECT + "::unit:App@src/App.php",
                PROJECT + "::unit:App\\Service"
            );
        assertThat(repository.findOutgoingRelationships(
                PROJECT, runId, RelationshipType.CALLS.name()))
            .extracting(relationship -> relationship.getToNodeId())
            .contains(helperId);
        assertThat(repository.findFunctionsByProjectFilePath(PROJECT, "src/App.php"))
            .extracting(function -> function.getId())
            .contains(helperId, runId);
    }

    @Test
    void processProtocolAcceptsPhpStaticExtractEndpoints() {
        Path projectRoot = phpParserRoot().resolve("fixtures/basic");
        Path sourceFile = projectRoot.resolve("src/App.php");
        ProcessCodeGraphParser parser = new ProcessCodeGraphParser(
            "php",
            List.of(System.getenv("PHP_CODE_GRAPH_CLI"), "--stdio"),
            Duration.ofSeconds(30)
        );

        var delta = parser.parse(new ParseRequest(
            PROJECT,
            "php",
            projectRoot.toString(),
            List.of(sourceFile.toString()),
            List.of(projectRoot.resolve("src").toString()),
            List.of(),
            "git@example/php-process-e2e.git",
            "main",
            ChangeType.SOURCE_ADDED,
            List.of(phpParserRoot().getParent().resolve("static-extract-php/examples/conformance/php-endpoints/rules/symfony-route.ser").toString()),
            List.of(),
            Map.of(),
            Map.of()
        ));

        assertThat(delta.endpoints())
            .extracting(endpoint -> endpoint.getMatchIdentity())
            .containsExactly("HTTP:GET:/api/run");
        assertThat(delta.relationships())
            .extracting(relationship -> relationship.getRelationshipType())
            .contains(RelationshipType.ENDPOINT_TO_FUNCTION);
    }

    /** PHP oracle: interface extension, class inheritance, trait use and overrides are separate. */
    @Test
    void phpNativeSemanticOracleSurvivesEnginePersistence() {
        Path root = phpParserRoot().resolve("fixtures/semantics/src");
        Path source = root.resolve("Relations.php");

        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        IncrementalUpdateService service = new IncrementalUpdateService(
            repository, repository, repository, repository, repository);
        service.handleFileAdded(
            PROJECT,
            source.toString(),
            "src/Relations.php",
            "git@example/php-semantics.git",
            "main",
            new String[0],
            new String[] {root.toString()});

        assertPhpSemanticOracle(repository);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEO4J_URI", matches = ".+")
    void phpNativeSemanticOracleMatchesPersistedNeo4jEdges() {
        Path root = phpParserRoot().resolve("fixtures/semantics/src");
        Path source = root.resolve("Relations.php");
        try (Neo4jContractGraph graph = Neo4jContractGraph.open(PROJECT)) {
            graph.service().handleFileAdded(
                PROJECT,
                source.toString(),
                "src/Relations.php",
                "git@example/php-semantics.git",
                "main",
                new String[0],
                new String[] {root.toString()});
            assertPhpSemanticOracle(graph.relationships());
        }
    }

    private void assertPhpSemanticOracle(CodeRelationshipRepository repository) {
        assertEdge(repository, "unit:App\\ChildGateway", "EXTENDS", "unit:App\\Gateway");
        assertEdge(repository, "unit:App\\Service", "EXTENDS", "unit:App\\Base");
        assertEdge(repository, "unit:App\\Service", "IMPLEMENTS", "unit:App\\ChildGateway");
        assertEdge(repository, "unit:App\\Service", "USES_TRAIT", "unit:App\\Logs");
        assertEdge(repository, "fn:App\\Service::run()", "OVERRIDES", "fn:App\\Base::run()");
        assertEdge(repository, "fn:App\\Service::send()", "OVERRIDES", "fn:App\\Gateway::send()");
        assertEdge(repository, "fn:App\\Service::receive()", "OVERRIDES", "fn:App\\ChildGateway::receive()");
        assertNoEdge(repository, "fn:App\\Service::hidden()", "OVERRIDES", "fn:App\\Base::hidden()");
        assertNoEdge(repository, "fn:App\\Unrelated::send()", "OVERRIDES", "fn:App\\Gateway::send()");
    }

    private void assertEdge(CodeRelationshipRepository repository, String from, String type, String to) {
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped(from), type))
            .as("PHP persisted edge %s -[%s]-> %s", from, type, to)
            .extracting(relationship -> relationship.getToNodeId())
            .contains(scoped(to));
    }

    private void assertNoEdge(CodeRelationshipRepository repository, String from, String type, String to) {
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped(from), type))
            .as("forbidden PHP edge %s -[%s]-> %s", from, type, to)
            .extracting(relationship -> relationship.getToNodeId())
            .doesNotContain(scoped(to));
    }

    private String scoped(String id) {
        return PROJECT + "::" + id;
    }

    private static Path phpParserRoot() {
        Path cli = Path.of(System.getenv("PHP_CODE_GRAPH_CLI")).toAbsolutePath();
        return cli.getParent().getParent();
    }
}
