package com.poseidon.codegraph.app;

import com.poseidon.codegraph.model.RelationshipType;
import com.poseidon.codegraph.engine.application.repository.CodeRelationshipRepository;
import com.poseidon.codegraph.starter.service.IncrementalUpdateService;
import com.poseidon.codegraph.starter.service.IncrementalUpdateSession;
import com.poseidon.codegraph.storage.memory.repository.InMemoryCodeGraphRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeEndpointRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeFunctionRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodePackageRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeRelationshipRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeUnitRepository;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-to-storage contract for Go's implicit interface implementation.
 *
 * <p>This deliberately asserts the persisted graph rather than only checking the parser JSON:
 * a relationship is not complete until the engine can query it after GraphDelta application.
 */
@EnabledIfEnvironmentVariable(named = "GO_CODE_GRAPH_CLI", matches = ".+")
class GoProcessParserEndToEndTest {

    private static final String PROJECT = "go-process-e2e";

    @BeforeAll
    static void configureExternalParser() {
        System.setProperty("codegraph.parser.process.languages", "go");
        System.setProperty("codegraph.parser.process.go.command",
            "'" + System.getenv("GO_CODE_GRAPH_CLI") + "' --stdio-stream");
        System.setProperty("codegraph.parser.process.go.streaming", "true");
        System.setProperty("codegraph.parser.process.timeoutSeconds", "60");
    }

    @AfterAll
    static void clearExternalParserConfig() {
        System.clearProperty("codegraph.parser.process.languages");
        System.clearProperty("codegraph.parser.process.go.command");
        System.clearProperty("codegraph.parser.process.go.streaming");
        System.clearProperty("codegraph.parser.process.timeoutSeconds");
    }

    @Test
    void sourceRelationsSurviveParserDeltaAndEnginePersistence() {
        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        IncrementalUpdateService service = new IncrementalUpdateService(
            repository, repository, repository, repository, repository
        );
        Path projectRoot = goParserRoot().resolve("testdata/iface");
        Path contractFile = projectRoot.resolve("contract/contract.go");
        Path sourceFile = projectRoot.resolve("iface.go");

        try (IncrementalUpdateSession session = service.openSession("go")) {
            session.handleFileAdded(
                PROJECT,
                contractFile.toString(),
                "contract/contract.go",
                "git@example/go-process-e2e.git",
                "main",
                new String[0],
                new String[] { projectRoot.toString() },
                List.of(),
                List.of()
            );
            session.handleFileAdded(
                PROJECT,
                sourceFile.toString(),
                "iface.go",
                "git@example/go-process-e2e.git",
                "main",
                new String[0],
                new String[] { projectRoot.toString() },
                List.of(),
                List.of()
            );
        }

        assertGoSemanticOracle(repository);
    }

    /**
     * Source oracle: iface.go declares Person as an implicit Greeter implementation,
     * Person.Greet supplies Greeter.Greet, and Speak invokes Greeter.Greet.
     * The assertions query Neo4j after Engine persistence; GraphDelta is not used as evidence.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "NEO4J_URI", matches = ".+")
    void sourceRelationsMatchPersistedNeo4jEdges() {
        String uri = System.getenv("NEO4J_URI");
        String username = environmentOrDefault("NEO4J_USERNAME", "neo4j");
        String password = environmentOrDefault("NEO4J_PASSWORD", "password");

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
            driver.verifyConnectivity();
            deleteProject(driver, PROJECT);

            Neo4jCodePackageRepository packages = new Neo4jCodePackageRepository(driver);
            Neo4jCodeUnitRepository units = new Neo4jCodeUnitRepository(driver);
            Neo4jCodeFunctionRepository functions = new Neo4jCodeFunctionRepository(driver);
            Neo4jCodeRelationshipRepository relationships = new Neo4jCodeRelationshipRepository(driver);
            Neo4jCodeEndpointRepository endpoints = new Neo4jCodeEndpointRepository(driver);
            IncrementalUpdateService service = new IncrementalUpdateService(
                packages, units, functions, relationships, endpoints
            );

            Path projectRoot = goParserRoot().resolve("testdata/iface");
            Path contractFile = projectRoot.resolve("contract/contract.go");
            Path sourceFile = projectRoot.resolve("iface.go");
            try (IncrementalUpdateSession session = service.openSession("go")) {
                session.handleFileAdded(
                    PROJECT,
                    contractFile.toString(),
                    "contract/contract.go",
                    "git@example/go-process-e2e.git",
                    "main",
                    new String[0],
                    new String[] { projectRoot.toString() },
                    List.of(),
                    List.of()
                );
                session.handleFileAdded(
                    PROJECT,
                    sourceFile.toString(),
                    "iface.go",
                    "git@example/go-process-e2e.git",
                    "main",
                    new String[0],
                    new String[] { projectRoot.toString() },
                    List.of(),
                    List.of()
                );
            }

            assertGoSemanticOracle(relationships);
        } finally {
            // Keep repeated local/CI runs isolated without touching other projects.
            try (Driver cleanup = GraphDatabase.driver(
                    uri,
                    AuthTokens.basic(username, password))) {
                deleteProject(cleanup, PROJECT);
            }
        }
    }

    private static void deleteProject(Driver driver, String projectName) {
        driver.executableQuery("MATCH (n {projectName: $projectName}) DETACH DELETE n")
            .withParameters(java.util.Map.of("projectName", projectName))
            .execute();
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void assertGoSemanticOracle(CodeRelationshipRepository relationships) {
        // Go has embedding and implicit interface satisfaction, not Java inheritance/override.
        assertEdge(relationships,
            "unit:example.com/iface.Person", "SATISFIES", "unit:example.com/iface.Greeter");
        assertEdge(relationships,
            "fn:example.com/iface.Person.Greet", "SATISFIES_METHOD", "fn:example.com/iface.Greeter.Greet");
        assertEdge(relationships,
            "unit:example.com/iface.LoudPerson", "EMBEDS", "unit:example.com/iface.Person");
        assertEdge(relationships,
            "unit:example.com/iface.Shouter", "EMBEDS", "unit:example.com/iface.Greeter");
        assertEdge(relationships,
            "fn:example.com/iface.EmbeddedChild.Ping", "SHADOWS", "fn:example.com/iface.EmbeddedBase.Ping");
        assertEdge(relationships,
            "unit:example.com/iface.PointerPerson", "SATISFIES", "unit:example.com/iface.PointerGreeter");
        assertEdge(relationships,
            "unit:example.com/iface.CrossPackageGreeter", "SATISFIES",
            "unit:example.com/iface/contract.ExternalGreeter");
        assertEdge(relationships,
            "fn:example.com/iface.Speak", RelationshipType.CALLS.name(), "fn:example.com/iface.Greeter.Greet");

        assertNoEdge(relationships,
            "unit:example.com/iface.Almost", "SATISFIES", "unit:example.com/iface.Partial");
        assertNoEdge(relationships,
            "fn:example.com/iface.Almost.First", "SATISFIES_METHOD", "fn:example.com/iface.Partial.First");
    }

    private static void assertEdge(CodeRelationshipRepository relationships, String from, String type, String to) {
        assertThat(relationships.findOutgoingRelationships(PROJECT, scoped(from), type))
            .as("Go persisted edge %s -[%s]-> %s", from, type, to)
            .extracting(relationship -> relationship.getToNodeId())
            .contains(scoped(to));
    }

    private static void assertNoEdge(CodeRelationshipRepository relationships, String from, String type, String to) {
        assertThat(relationships.findOutgoingRelationships(PROJECT, scoped(from), type))
            .as("forbidden Go edge %s -[%s]-> %s", from, type, to)
            .extracting(relationship -> relationship.getToNodeId())
            .doesNotContain(scoped(to));
    }

    private static String scoped(String id) {
        return PROJECT + "::" + id;
    }

    private static Path goParserRoot() {
        Path cli = Path.of(System.getenv("GO_CODE_GRAPH_CLI")).toAbsolutePath();
        return cli.getParent().getParent();
    }
}
