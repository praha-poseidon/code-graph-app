package com.poseidon.codegraph.app;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Source → standalone Java parser → Engine → persisted graph contract. */
@EnabledIfEnvironmentVariable(named = "JAVA_CODE_GRAPH_CLI_JAR", matches = ".+")
class JavaProcessParserEndToEndTest {
    private static final String PROJECT = "java-process-e2e";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureExternalParser() {
        System.setProperty("codegraph.parser.process.languages", "java");
        System.setProperty("codegraph.parser.process.java.command",
            "java -jar '" + System.getenv("JAVA_CODE_GRAPH_CLI_JAR") + "' --stdio-stream");
        System.setProperty("codegraph.parser.process.java.streaming", "true");
        System.setProperty("codegraph.parser.process.timeoutSeconds", "60");
    }

    @AfterAll
    static void clearExternalParserConfig() {
        System.clearProperty("codegraph.parser.process.languages");
        System.clearProperty("codegraph.parser.process.java.command");
        System.clearProperty("codegraph.parser.process.java.streaming");
        System.clearProperty("codegraph.parser.process.timeoutSeconds");
    }

    @Test
    void java21GenericRelationshipsSurviveIncrementalMemoryPersistence() throws Exception {
        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        persistFixture(new IncrementalUpdateService(repository, repository, repository, repository, repository));
        assertSourceOracle(new PersistedRepositories(repository, repository, repository, repository, repository));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEO4J_URI", matches = ".+")
    void java21GenericSourceOracleMatchesNeo4j() throws Exception {
        String uri = System.getenv("NEO4J_URI");
        String username = environmentOrDefault("NEO4J_USERNAME", "neo4j");
        String password = environmentOrDefault("NEO4J_PASSWORD", "password");
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
            driver.verifyConnectivity();
            deleteProject(driver);
            PersistedRepositories graph = new PersistedRepositories(
                new Neo4jCodePackageRepository(driver),
                new Neo4jCodeUnitRepository(driver),
                new Neo4jCodeFunctionRepository(driver),
                new Neo4jCodeRelationshipRepository(driver),
                new Neo4jCodeEndpointRepository(driver));
            persistFixture(new IncrementalUpdateService(
                graph.packages(), graph.units(), graph.functions(), graph.relationships(), graph.endpoints()));
            assertSourceOracle(graph);
        } finally {
            try (Driver cleanup = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
                deleteProject(cleanup);
            }
        }
    }

    @Test
    void registeredProjectScopesKeepRealSourceRelationshipsIsolated() throws Exception {
        var a = new com.poseidon.codegraph.app.config.RepositoryIdentity("a", "key-a", "github.com/team-a/demo");
        var b = new com.poseidon.codegraph.app.config.RepositoryIdentity("b", "key-b", "github.com/team-b/demo");
        var repository = new InMemoryCodeGraphRepository();
        var service = new IncrementalUpdateService(repository, repository, repository, repository, repository);
        var scopes = List.of(a.graphScope(), b.graphScope());
        Oracle oracle = MAPPER.readValue(fixtureRoot().resolve("source-oracle.json").toFile(), Oracle.class);
        for (String scope : scopes) persistFixture(service, scope);
        for (String scope : scopes) {
            for (ExpectedRelationship edge : oracle.relationships()) {
                assertThat(repository.findOutgoingRelationships(scope, scope + "::" + edge.from(), edge.type()))
                    .extracting(com.poseidon.codegraph.engine.application.model.CodeRelationshipDO::getToNodeId)
                    .contains(scope + "::" + edge.to());
            }
            assertThat(repository.findRelationshipsByProject(scope)).allSatisfy(edge -> {
                assertThat(edge.getFromNodeId()).startsWith(scope + "::");
                assertThat(edge.getToNodeId()).startsWith(scope + "::");
            });
        }
        int before = repository.findRelationshipsByProject(scopes.getFirst()).size();
        persistFixture(service, scopes.getFirst());
        assertThat(repository.findRelationshipsByProject(scopes.getFirst())).hasSize(before);
        Path sourceRoot = fixtureRoot().resolve("src/main/java");
        try (var session = service.openSession("java")) {
            session.handleFileDeleted(scopes.getFirst(), sourceRoot.resolve("demo/app/AdvanceCaller.java").toString(),
                "demo/app/AdvanceCaller.java", "git@example/java-process-e2e.git", "main", new String[0], new String[]{sourceRoot.toString()});
        }
        String caller = "fn:demo.app.AdvanceCaller.run(demo.api.AdvanceService,demo.model.AdvanceApply)";
        assertThat(repository.findFunctionsByProject(scopes.getFirst())).noneMatch(fn -> fn.getId().equals(scopes.getFirst() + "::" + caller));
        for (String untouched : scopes.subList(1, scopes.size())) {
            assertThat(repository.findFunctionsByProject(untouched)).anyMatch(fn -> fn.getId().equals(untouched + "::" + caller));
        }
    }

    private static void persistFixture(IncrementalUpdateService service) {
        persistFixture(service, PROJECT);
    }

    private static void persistFixture(IncrementalUpdateService service, String project) {
        Path root = fixtureRoot();
        Path sourceRoot = root.resolve("src/main/java");
        try (IncrementalUpdateSession session = service.openSession("java")) {
            for (String relative : List.of(
                    "demo/api/CrudService.java",
                    "demo/api/AdvanceService.java",
                    "demo/base/ServiceImpl.java",
                    "demo/impl/AdvanceMapper.java",
                    "demo/model/AdvanceApply.java",
                    "demo/impl/AdvanceServiceImpl.java",
                    "demo/app/AdvanceCaller.java")) {
                session.handleFileAdded(
                    project,
                    sourceRoot.resolve(relative).toString(),
                    relative,
                    "git@example/java-process-e2e.git",
                    "main",
                    new String[0],
                    new String[] {sourceRoot.toString()},
                    List.of(),
                    List.of());
            }
        }
    }

    private static void assertSourceOracle(PersistedRepositories graph) throws Exception {
        Oracle oracle = MAPPER.readValue(fixtureRoot().resolve("source-oracle.json").toFile(), Oracle.class);
        assertThat(graph.units().findExistingUnitsByQualifiedNames(
            PROJECT, oracle.units().stream().map(name -> scoped("unit:" + name)).toList()))
            .containsExactlyInAnyOrderElementsOf(oracle.units().stream().map(name -> scoped("unit:" + name)).toList());

        for (ExpectedRelationship expected : oracle.relationships()) {
            assertThat(graph.relationships().findOutgoingRelationships(PROJECT, scoped(expected.from()), expected.type()))
                .as("persisted edge %s -[%s]-> %s", expected.from(), expected.type(), expected.to())
                .extracting(relationship -> relationship.getToNodeId())
                .contains(scoped(expected.to()));
        }
        for (ExpectedRelationship forbidden : oracle.forbiddenRelationships()) {
            assertThat(graph.relationships().findOutgoingRelationships(PROJECT, scoped(forbidden.from()), forbidden.type()))
                .extracting(relationship -> relationship.getToNodeId())
                .doesNotContain(scoped(forbidden.to()));
        }
    }

    private static Path fixtureRoot() {
        Path current = Path.of(System.getenv("JAVA_CODE_GRAPH_CLI_JAR")).toAbsolutePath().getParent();
        while (current != null) {
            Path fixture = current.resolve("src/test/resources/fixtures/contract");
            if (Files.isDirectory(fixture)) {
                return fixture;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate Java contract fixture from JAVA_CODE_GRAPH_CLI_JAR");
    }

    private static String scoped(String id) {
        return PROJECT + "::" + id;
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

    private record PersistedRepositories(
        CodePackageRepository packages,
        CodeUnitRepository units,
        CodeFunctionRepository functions,
        CodeRelationshipRepository relationships,
        CodeEndpointRepository endpoints
    ) {}

    private record Oracle(
        List<String> units,
        List<ExpectedRelationship> relationships,
        List<ExpectedRelationship> forbiddenRelationships
    ) {}

    private record ExpectedRelationship(String from, String type, String to) {}
}
