package com.poseidon.codegraph.app;

import com.poseidon.codegraph.engine.application.model.CodeEndpointDO;
import com.poseidon.codegraph.engine.application.repository.CodeEndpointRepository;
import com.poseidon.codegraph.engine.application.repository.CodeFunctionRepository;
import com.poseidon.codegraph.engine.application.repository.CodePackageRepository;
import com.poseidon.codegraph.engine.application.repository.CodeRelationshipRepository;
import com.poseidon.codegraph.engine.application.repository.CodeUnitRepository;
import com.poseidon.codegraph.starter.service.IncrementalUpdateService;
import com.poseidon.codegraph.starter.service.IncrementalUpdateSession;
import com.poseidon.codegraph.storage.memory.repository.InMemoryCodeGraphRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeEndpointRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeFunctionRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodePackageRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeRelationshipRepository;
import com.poseidon.codegraph.storage.neo4j.repository.Neo4jCodeUnitRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Source → parser process → Engine → persisted graph contract for Swift semantics. */
@EnabledIfEnvironmentVariable(named = "SWIFT_CODE_GRAPH_CLI", matches = ".+")
class SwiftProcessParserEndToEndTest {

    private static final String PROJECT = "swift-process-e2e";

    @BeforeAll
    static void configureExternalParser() {
        System.setProperty("codegraph.parser.process.languages", "swift");
        System.setProperty("codegraph.parser.process.swift.command",
            "'" + System.getenv("SWIFT_CODE_GRAPH_CLI") + "' --stdio-stream");
        System.setProperty("codegraph.parser.process.swift.streaming", "true");
        System.setProperty("codegraph.parser.process.timeoutSeconds", "60");
    }

    @AfterAll
    static void clearExternalParserConfig() {
        System.clearProperty("codegraph.parser.process.languages");
        System.clearProperty("codegraph.parser.process.swift.command");
        System.clearProperty("codegraph.parser.process.swift.streaming");
        System.clearProperty("codegraph.parser.process.timeoutSeconds");
    }

    @Test
    void swiftRelationshipsSurviveIncrementalEnginePersistence() {
        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        persistFixture(new IncrementalUpdateService(repository, repository, repository, repository, repository));
        assertPersisted(new PersistedRepositories(repository, repository, repository, repository, repository));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEO4J_URI", matches = ".+")
    void sourceOracleMatchesPersistedNeo4jEdges() {
        String uri = System.getenv("NEO4J_URI");
        String username = environmentOrDefault("NEO4J_USERNAME", "neo4j");
        String password = environmentOrDefault("NEO4J_PASSWORD", "password");
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
            driver.verifyConnectivity();
            deleteProject(driver);
            Neo4jCodePackageRepository packages = new Neo4jCodePackageRepository(driver);
            Neo4jCodeUnitRepository units = new Neo4jCodeUnitRepository(driver);
            Neo4jCodeFunctionRepository functions = new Neo4jCodeFunctionRepository(driver);
            Neo4jCodeRelationshipRepository relationships = new Neo4jCodeRelationshipRepository(driver);
            Neo4jCodeEndpointRepository endpoints = new Neo4jCodeEndpointRepository(driver);
            persistFixture(new IncrementalUpdateService(packages, units, functions, relationships, endpoints));
            assertPersisted(new PersistedRepositories(packages, units, functions, relationships, endpoints));
        } finally {
            try (Driver cleanup = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
                deleteProject(cleanup);
            }
        }
    }

    private static void persistFixture(IncrementalUpdateService service) {
        Path root = fixtureRoot();
        try (IncrementalUpdateSession session = service.openSession("swift")) {
            for (String relative : List.of(
                    "Sources/Demo/Contracts.swift",
                    "Sources/Demo/Service.swift",
                    "Sources/Demo/Caller.swift",
                    "Sources/Demo/Controller.swift")) {
                session.handleFileAdded(
                    PROJECT,
                    root.resolve(relative).toString(),
                    relative,
                    "git@example/swift-process-e2e.git",
                    "main",
                    new String[0],
                    new String[] { root.resolve("Sources").toString() },
                    List.of(swiftHttpRule()),
                    List.of()
                );
            }
        }
    }

    private static void assertPersisted(PersistedRepositories graph) {
        assertThat(graph.packages().findExistingPackagesByQualifiedNames(PROJECT, List.of(scoped("pkg:Demo"))))
            .containsExactly(scoped("pkg:Demo"));
        assertThat(graph.units().findUnitsByProjectFilePath(PROJECT, "Sources/Demo/Contracts.swift"))
            .extracting(unit -> unit.getQualifiedName())
            .contains("Demo.ParentGateway", "Demo.Gateway", "Demo.BaseService");
        assertThat(graph.units().findUnitsByProjectFilePath(PROJECT, "Sources/Demo/Service.swift"))
            .extracting(unit -> unit.getQualifiedName())
            .contains("Demo.DemoMapper", "Demo.Service", "Demo@Sources/Demo/Service.swift");
        assertThat(graph.units().findUnitsByProjectFilePath(PROJECT, "Sources/Demo/Controller.swift"))
            .extracting(unit -> unit.getQualifiedName())
            .contains("Demo.Controller");
        assertThat(graph.functions().findFunctionsByProjectFilePath(PROJECT, "Sources/Demo/Service.swift"))
            .extracting(function -> function.getQualifiedName())
            .contains(
                "Demo.Service::init",
                "Demo.Service::run",
                "Demo.Service::send",
                "Demo.Service::inheritedRequirement",
                "Demo.helper",
                "Demo.construct",
                "Demo.invokeInherited"
            );
        assertThat(graph.functions().findFunctionsByProjectFilePath(PROJECT, "Sources/Demo/Caller.swift"))
            .extracting(function -> function.getQualifiedName())
            .contains("Demo.handle");
        assertThat(graph.functions().findFunctionsByProjectFilePath(PROJECT, "Sources/Demo/Controller.swift"))
            .extracting(function -> function.getQualifiedName())
            .contains("Demo.Controller::order", "Demo.fetchOrder");

        List<CodeEndpointDO> persistedEndpoints = graph.endpoints()
            .findEndpointsByProjectFilePath(PROJECT, "Sources/Demo/Controller.swift")
            .stream().toList();
        CodeEndpointDO endpoint = persistedEndpoints.stream()
            .filter(candidate -> "HTTP:GET:/orders/{id}".equals(candidate.getMatchIdentity()))
            .filter(candidate -> "inbound".equals(candidate.getDirection()))
            .findFirst()
            .orElseThrow();
        assertThat(endpoint.getEndpointType()).isEqualTo("HTTP");
        assertThat(endpoint.getDirection()).isEqualTo("inbound");
        assertThat(endpoint.getPath()).isEqualTo("/orders/{id}");

        CodeRelationshipRepository repository = graph.relationships();
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("pkg:Demo"), "PACKAGE_TO_UNIT"))
            .extracting(relationship -> relationship.getToNodeId())
            .contains(scoped("unit:Demo.Service"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("unit:Demo.Service"), "UNIT_TO_FUNCTION"))
            .extracting(relationship -> relationship.getToNodeId())
            .contains(scoped("fn:Demo.Service::run()"));
        assertThat(repository.findOutgoingRelationships(PROJECT, endpoint.getId(), "ENDPOINT_TO_FUNCTION"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("fn:Demo.Controller::order()"));
        CodeEndpointDO outbound = persistedEndpoints.stream()
            .filter(candidate -> "HTTP:GET:/orders/{id}".equals(candidate.getMatchIdentity()))
            .filter(candidate -> "outbound".equals(candidate.getDirection()))
            .findFirst()
            .orElseThrow();
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("fn:Demo.fetchOrder()"), "FUNCTION_TO_ENDPOINT"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(outbound.getId());
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("unit:Demo.Gateway"), "REFINES"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("unit:Demo.ParentGateway"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("unit:Demo.Service"), "INHERITS"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("unit:Demo.BaseService"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("unit:Demo.Service"), "CONFORMS"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("unit:Demo.Gateway"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("fn:Demo.Service::run()"), "OVERRIDES"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("fn:Demo.BaseService::run()"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("fn:Demo.Service::send(String)"), "WITNESSES"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("fn:Demo.Gateway::send(String)"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("fn:Demo.Service::inheritedRequirement(String)"), "WITNESSES"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("fn:Demo.ParentGateway::inheritedRequirement(String)"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("fn:Demo.Service::run()"), "CALLS"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("fn:Demo.helper()"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("fn:Demo.handle(Gateway)"), "CALLS"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("fn:Demo.Gateway::send(String)"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("fn:Demo.construct()"), "CALLS"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("fn:Demo.Service::init()"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("fn:Demo.invokeInherited(Service)"), "CALLS"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(scoped("fn:Demo.BaseService::baseOnly()"));
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("unit:Demo.Service"), "IMPLEMENTS"))
            .isEmpty();
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped("fn:Demo.Service::send(String)"), "OVERRIDES"))
            .isEmpty();
    }

    private static String swiftHttpRule() {
        return """
            rule "swift-http" {
              fact http
              find attribute GET
              let route =
                from attribute take attr(0)
                | normalize httpPath
              build {
                endpointType: "HTTP"
                direction: "inbound"
                method: "GET"
                path: route
              }
            }
            rule "swift-client" {
              fact http
              find call client.get
              let route =
                from argument[0] take value
                | normalize httpPath
              build {
                endpointType: "HTTP"
                direction: "outbound"
                method: "GET"
                path: route
              }
            }
            """;
    }

    private record PersistedRepositories(
        CodePackageRepository packages,
        CodeUnitRepository units,
        CodeFunctionRepository functions,
        CodeRelationshipRepository relationships,
        CodeEndpointRepository endpoints
    ) {}

    private static String scoped(String id) {
        return PROJECT + "::" + id;
    }

    private static Path fixtureRoot() {
        Path current = Path.of(System.getenv("SWIFT_CODE_GRAPH_CLI")).toAbsolutePath().getParent();
        while (current != null) {
            Path fixture = current.resolve("Tests/Fixtures/Contract");
            if (Files.isDirectory(fixture)) {
                return fixture;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate Tests/Fixtures/Contract from SWIFT_CODE_GRAPH_CLI");
    }

    private static void deleteProject(Driver driver) {
        driver.executableQuery("MATCH (n {projectName: $projectName}) DETACH DELETE n")
            .withParameters(Map.of("projectName", PROJECT))
            .execute();
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
