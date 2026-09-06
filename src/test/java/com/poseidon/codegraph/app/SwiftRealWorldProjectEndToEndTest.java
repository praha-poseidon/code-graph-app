package com.poseidon.codegraph.app;

import com.poseidon.codegraph.engine.application.model.CodeFunctionDO;
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

/** IceCubesApp pinned-source → Swift parser session → Engine → persisted graph. */
@EnabledIfEnvironmentVariable(named = "SWIFT_REAL_WORLD_PROJECT_ROOT", matches = ".+")
class SwiftRealWorldProjectEndToEndTest {

    private static final String PROJECT = "swift-icecubes-realworld";
    private static final List<String> SOURCE_FILES = List.of(
        "Packages/StatusKit/Sources/StatusKit/List/StatusesFetcher.swift",
        "Packages/Timeline/Sources/Timeline/actors/TimelineStatusFetcher.swift",
        "Packages/Timeline/Tests/TimelineTests/MockTimelineStatusFetcher.swift",
        "Packages/Timeline/Sources/Timeline/actors/TimelineCache.swift",
        "Packages/Timeline/Sources/Timeline/View/TimelineViewModel.swift",
        "Packages/Account/Sources/Account/Detail/Tabs/Base/AccountTabFetcher.swift",
        "Packages/Account/Sources/Account/Detail/Tabs/BookmarksTab.swift",
        "Packages/StatusKit/Sources/StatusKit/List/StatusesListView.swift"
    );

    @BeforeAll
    static void configureExternalParser() {
        String command = System.getenv("SWIFT_CODE_GRAPH_CLI");
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("SWIFT_CODE_GRAPH_CLI is required for the real-world Swift test");
        }
        System.setProperty("codegraph.parser.process.languages", "swift");
        System.setProperty("codegraph.parser.process.swift.command", "'" + command + "' --stdio-stream");
        System.setProperty("codegraph.parser.process.swift.streaming", "true");
        System.setProperty("codegraph.parser.process.timeoutSeconds", "120");
    }

    @AfterAll
    static void clearExternalParserConfig() {
        System.clearProperty("codegraph.parser.process.languages");
        System.clearProperty("codegraph.parser.process.swift.command");
        System.clearProperty("codegraph.parser.process.swift.streaming");
        System.clearProperty("codegraph.parser.process.timeoutSeconds");
    }

    @Test
    void realBusinessSourcesPersistExactSwiftSemanticsInMemory() {
        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        persistProject(new IncrementalUpdateService(repository, repository, repository, repository, repository));
        assertPersisted(new PersistedRepositories(repository, repository, repository, repository, repository));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEO4J_URI", matches = ".+")
    void realBusinessSourceOracleMatchesNeo4j() {
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
            persistProject(new IncrementalUpdateService(packages, units, functions, relationships, endpoints));
            assertPersisted(new PersistedRepositories(packages, units, functions, relationships, endpoints));
        } finally {
            try (Driver cleanup = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
                deleteProject(cleanup);
            }
        }
    }

    private static void persistProject(IncrementalUpdateService service) {
        Path root = Path.of(System.getenv("SWIFT_REAL_WORLD_PROJECT_ROOT")).toAbsolutePath();
        for (String relative : SOURCE_FILES) {
            assertThat(Files.isRegularFile(root.resolve(relative)))
                .as("pinned IceCubesApp source file %s", relative)
                .isTrue();
        }
        try (IncrementalUpdateSession session = service.openSession("swift")) {
            for (String relative : SOURCE_FILES) {
                session.handleFileAdded(
                    PROJECT,
                    root.resolve(relative).toString(),
                    relative,
                    "https://github.com/Dimillian/IceCubesApp.git",
                    "b2db3033fbf67a97b54d25d6dac2df8a029b26b1",
                    new String[0],
                    new String[] { root.toString() },
                    List.of(),
                    List.of()
                );
            }
        }
    }

    private static void assertPersisted(PersistedRepositories graph) {
        assertThat(graph.packages().findExistingPackagesByQualifiedNames(PROJECT, List.of(
            scoped("pkg:Timeline"), scoped("pkg:StatusKit"), scoped("pkg:Account"))))
            .contains(scoped("pkg:Timeline"), scoped("pkg:StatusKit"), scoped("pkg:Account"));

        assertThat(graph.units().findUnitsByProjectFilePath(PROJECT, SOURCE_FILES.get(0)))
            .extracting(unit -> unit.getQualifiedName())
            .contains("StatusKit.StatusesFetcher", "StatusKit.GapLoadingFetcher");
        assertThat(graph.units().findUnitsByProjectFilePath(PROJECT, SOURCE_FILES.get(1)))
            .extracting(unit -> unit.getQualifiedName())
            .contains("Timeline.TimelineStatusFetching", "Timeline.TimelineStatusFetcher");
        assertThat(graph.units().findUnitsByProjectFilePath(PROJECT, SOURCE_FILES.get(2)))
            .filteredOn(unit -> "TimelineTests.MockTimelineStatusFetcher".equals(unit.getQualifiedName()))
            .singleElement()
            .extracting(unit -> unit.getUnitType())
            .isEqualTo("actor");
        assertThat(graph.units().findUnitsByProjectFilePath(PROJECT, SOURCE_FILES.get(3)))
            .extracting(unit -> unit.getQualifiedName())
            .contains(
                "Timeline.TimelineCache",
                "Timeline.TimelineCache.CachedTimelineItem",
                "Timeline.TimelineCache.CachedTimelineItem.Kind"
            );
        assertThat(graph.units().findUnitsByProjectFilePath(PROJECT, SOURCE_FILES.get(4)))
            .extracting(unit -> unit.getQualifiedName())
            .contains("Timeline.TimelineViewModel");
        assertThat(graph.units().findUnitsByProjectFilePath(PROJECT, SOURCE_FILES.get(7)))
            .filteredOn(unit -> "StatusKit.StatusesListView".equals(unit.getQualifiedName()))
            .singleElement()
            .extracting(unit -> unit.getUnitType())
            .isEqualTo("struct");

        CodeFunctionDO asyncFunction = graph.functions()
            .findFunctionsByProjectFilePath(PROJECT, SOURCE_FILES.get(1)).stream()
            .filter(function -> "Timeline.TimelineStatusFetcher::fetchFirstPage".equals(function.getQualifiedName()))
            .findFirst()
            .orElseThrow();
        assertThat(asyncFunction.getIsAsync()).isTrue();

        CodeRelationshipRepository relationships = graph.relationships();
        assertTargets(relationships, "unit:StatusKit.GapLoadingFetcher", "REFINES",
            "unit:StatusKit.StatusesFetcher");
        assertTargets(relationships, "unit:Timeline.TimelineStatusFetcher", "CONFORMS",
            "unit:Timeline.TimelineStatusFetching");
        assertTargets(relationships, "unit:TimelineTests.MockTimelineStatusFetcher", "CONFORMS",
            "unit:Timeline.TimelineStatusFetching");
        assertTargets(relationships, "unit:Timeline.TimelineViewModel", "CONFORMS",
            "unit:StatusKit.GapLoadingFetcher");
        assertTargets(relationships,
            "fn:Timeline.TimelineStatusFetcher::fetchFirstPage(MastodonClient,TimelineFilter)", "WITNESSES",
            "fn:Timeline.TimelineStatusFetching::fetchFirstPage(MastodonClient,TimelineFilter)");
        assertTargets(relationships,
            "fn:TimelineTests.MockTimelineStatusFetcher::fetchFirstPage(MastodonClient,TimelineFilter)", "WITNESSES",
            "fn:Timeline.TimelineStatusFetching::fetchFirstPage(MastodonClient,TimelineFilter)");
        assertTargets(relationships,
            "fn:Timeline.TimelineViewModel::fetchFirstPage(MastodonClient)", "CALLS",
            "fn:Timeline.TimelineStatusFetching::fetchFirstPage(MastodonClient,TimelineFilter)");
        assertTargets(relationships,
            "fn:Timeline.TimelineCache::set([TimelineItem],String,String)", "CALLS",
            "fn:Timeline.TimelineCache::storageFor(String,String)");
        assertTargets(relationships, "unit:Account.BookmarksTabFetcher", "INHERITS",
            "unit:Account.AccountTabFetcher");
        assertTargets(relationships,
            "fn:Account.BookmarksTabFetcher::fetchNewestStatuses(Bool)", "OVERRIDES",
            "fn:Account.AccountTabFetcher::fetchNewestStatuses(Bool)");

        assertThat(relationships.findOutgoingRelationships(
            PROJECT, scoped("unit:Timeline.TimelineStatusFetcher"), "IMPLEMENTS")).isEmpty();
        assertThat(relationships.findOutgoingRelationships(
            PROJECT, scoped("unit:Timeline.TimelineStatusFetcher"), "INHERITS")).isEmpty();
        assertThat(relationships.findOutgoingRelationships(
                PROJECT, scoped("fn:Timeline.TimelineViewModel::fetchFirstPage(MastodonClient)"), "CALLS"))
            .extracting(relationship -> relationship.getToNodeId())
            .doesNotContain(scoped("placeholder:fn:Timeline.statusFetcher.fetchFirstPage()"));
    }

    private static void assertTargets(
        CodeRelationshipRepository repository,
        String from,
        String type,
        String... expectedTargets
    ) {
        assertThat(repository.findOutgoingRelationships(PROJECT, scoped(from), type))
            .extracting(relationship -> relationship.getToNodeId())
            .contains(expectedTargetsAsScoped(expectedTargets));
    }

    private static String[] expectedTargetsAsScoped(String[] values) {
        String[] result = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = scoped(values[index]);
        }
        return result;
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
}
