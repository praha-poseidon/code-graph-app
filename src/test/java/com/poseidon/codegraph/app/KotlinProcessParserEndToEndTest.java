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

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "KOTLIN_CODE_GRAPH_CLI", matches = ".+")
class KotlinProcessParserEndToEndTest {

    private static final String SEMANTIC_PROJECT = "kotlin-semantics-e2e";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void configureExternalParser() {
        System.setProperty("codegraph.parser.process.languages", "kotlin");
        System.setProperty("codegraph.parser.process.kotlin.command",
            "'" + System.getenv("KOTLIN_CODE_GRAPH_CLI") + "' --stdio");
        System.setProperty("codegraph.parser.process.timeoutSeconds", "30");
    }

    @AfterAll
    static void clearExternalParserConfig() {
        System.clearProperty("codegraph.parser.process.languages");
        System.clearProperty("codegraph.parser.process.kotlin.command");
        System.clearProperty("codegraph.parser.process.timeoutSeconds");
    }

    @Test
    void processProtocolAcceptsNativeKotlinGraphAndCallerEndpoints() {
        Path parserRoot = Path.of(System.getenv("KOTLIN_CODE_GRAPH_CLI")).toAbsolutePath().getParent().getParent();
        Path projectRoot = parserRoot.resolve("testdata/basic");
        ProcessCodeGraphParser parser = new ProcessCodeGraphParser(
            "kotlin",
            List.of(System.getenv("KOTLIN_CODE_GRAPH_CLI"), "--stdio"),
            Duration.ofSeconds(30)
        );

        var delta = parser.parse(new ParseRequest(
            "kotlin-process-e2e",
            "kotlin",
            projectRoot.toString(),
            List.of(projectRoot.resolve("App.kt").toString()),
            List.of(projectRoot.toString()),
            List.of(),
            "git@example/kotlin-process-e2e.git",
            "main",
            ChangeType.SOURCE_ADDED,
            List.of(parserRoot.resolve("testdata/rules/http.ser").toString()),
            List.of(),
            Map.of(),
            Map.of()
        ));

        assertThat(delta.scope().language()).isEqualTo("kotlin");
        assertThat(delta.functions()).extracting(function -> function.getName())
            .contains("helper", "run", "<file-init>");
        assertThat(delta.endpoints()).extracting(endpoint -> endpoint.getMatchIdentity())
            .containsExactly("HTTP:GET:/api/run");
        assertThat(delta.relationships()).extracting(relationship -> relationship.getRelationshipType())
            .contains(RelationshipType.CALLS, RelationshipType.ENDPOINT_TO_FUNCTION);
    }

    @Test
    void processProtocolPassesDependencyClasspathToKotlinBinding() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        Path dependencySource = tempDir.resolve("dependency/dep/External.java");
        Path dependencyClasses = Files.createDirectories(tempDir.resolve("classes"));
        Files.createDirectories(dependencySource.getParent());
        Files.writeString(dependencySource, """
            package dep;
            public final class External {
                public String call(String value) { return value; }
            }
            """);
        int compilerResult = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-proc:none",
            "-d", dependencyClasses.toString(),
            dependencySource.toString()
        );
        assertThat(compilerResult).isZero();
        Path kotlinSource = projectRoot.resolve("Use.kt");
        Files.writeString(kotlinSource, """
            package demo
            import dep.External
            fun run(external: External) = external.call("value")
            """);

        ProcessCodeGraphParser parser = new ProcessCodeGraphParser(
            "kotlin",
            List.of(System.getenv("KOTLIN_CODE_GRAPH_CLI"), "--stdio"),
            Duration.ofSeconds(30)
        );
        var delta = parser.parse(new ParseRequest(
            "kotlin-classpath-e2e",
            "kotlin",
            projectRoot.toString(),
            List.of(kotlinSource.toString()),
            List.of(projectRoot.toString()),
            List.of(dependencyClasses.toString()),
            null,
            null,
            ChangeType.SOURCE_ADDED,
            List.of(),
            List.of(),
            Map.of(),
            Map.of()
        ));

        assertThat(delta.relationships())
            .filteredOn(relationship -> relationship.getRelationshipType() == RelationshipType.CALLS)
            .extracting(relationship -> relationship.getToNodeId())
            .anyMatch(id -> id.startsWith("fn:dep.External::call(java.lang.String)"));
        assertThat(delta.diagnostics()).isEmpty();
    }

    /**
     * Kotlin oracle: interface inheritance is INHERITS, class conformance is IMPLEMENTS,
     * and only actual override declarations receive OVERRIDES edges.
     */
    @Test
    void kotlinNativeSemanticOracleSurvivesEnginePersistence() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("kotlin-semantics"));
        Path source = root.resolve("Relations.kt");
        Files.writeString(source, """
            package demo
            interface Gateway { fun send(value: String): String }
            interface ChildGateway : Gateway { fun receive(): String }
            open class Base { open fun run() = Unit }
            class Service : Base(), ChildGateway {
                override fun run() = Unit
                override fun send(value: String): String = value
                override fun receive(): String = "ok"
            }
            class Unrelated { fun send(value: String): String = value }
            """);

        ProcessCodeGraphParser parser = new ProcessCodeGraphParser(
            "kotlin",
            List.of(System.getenv("KOTLIN_CODE_GRAPH_CLI"), "--stdio"),
            Duration.ofSeconds(30));
        var delta = parser.parse(new ParseRequest(
            SEMANTIC_PROJECT,
            "kotlin",
            root.toString(),
            List.of(source.toString()),
            List.of(root.toString()),
            List.of(),
            "git@example/kotlin-semantics.git",
            "main",
            ChangeType.SOURCE_ADDED,
            List.of(),
            List.of(),
            Map.of(),
            Map.of()));

        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        new IncrementalUpdateService(repository, repository, repository, repository, repository)
            .applyGraphDelta(delta);
        String project = SEMANTIC_PROJECT;

        assertEdge(repository, project, "unit:demo.ChildGateway", "INHERITS", "unit:demo.Gateway");
        assertEdge(repository, project, "unit:demo.Service", "INHERITS", "unit:demo.Base");
        assertEdge(repository, project, "unit:demo.Service", "IMPLEMENTS", "unit:demo.ChildGateway");
        assertEdge(repository, project, "fn:demo.Service::run()", "OVERRIDES", "fn:demo.Base::run()");
        assertEdge(repository, project, "fn:demo.Service::send(String)", "OVERRIDES", "fn:demo.Gateway::send(String)");
        assertEdge(repository, project, "fn:demo.Service::receive()", "OVERRIDES", "fn:demo.ChildGateway::receive()");
        assertThat(repository.findOutgoingRelationships(
                project, scoped(project, "fn:demo.Unrelated::send(String)"), "OVERRIDES"))
            .as("unrelated Kotlin method must not override Gateway.send")
            .extracting(relationship -> relationship.getToNodeId())
            .doesNotContain(scoped(project, "fn:demo.Gateway::send(String)"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "NEO4J_URI", matches = ".+")
    void kotlinNativeSemanticOracleMatchesPersistedNeo4jEdges() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("kotlin-semantics-neo4j"));
        Path source = root.resolve("Relations.kt");
        Files.writeString(source, """
            package demo
            interface Gateway { fun send(value: String): String }
            interface ChildGateway : Gateway { fun receive(): String }
            open class Base { open fun run() = Unit }
            class Service : Base(), ChildGateway {
                override fun run() = Unit
                override fun send(value: String): String = value
                override fun receive(): String = "ok"
            }
            class Unrelated { fun send(value: String): String = value }
            """);

        try (Neo4jContractGraph graph = Neo4jContractGraph.open(SEMANTIC_PROJECT)) {
            graph.service().handleFileAdded(
                SEMANTIC_PROJECT,
                source.toString(),
                "Relations.kt",
                "git@example/kotlin-semantics.git",
                "main",
                new String[0],
                new String[] {root.toString()});
            CodeRelationshipRepository relationships = graph.relationships();
            assertEdge(relationships, SEMANTIC_PROJECT, "unit:demo.ChildGateway", "INHERITS", "unit:demo.Gateway");
            assertEdge(relationships, SEMANTIC_PROJECT, "unit:demo.Service", "INHERITS", "unit:demo.Base");
            assertEdge(relationships, SEMANTIC_PROJECT, "unit:demo.Service", "IMPLEMENTS", "unit:demo.ChildGateway");
            assertEdge(relationships, SEMANTIC_PROJECT, "fn:demo.Service::run()", "OVERRIDES", "fn:demo.Base::run()");
            assertEdge(relationships, SEMANTIC_PROJECT, "fn:demo.Service::send(String)", "OVERRIDES", "fn:demo.Gateway::send(String)");
            assertEdge(relationships, SEMANTIC_PROJECT, "fn:demo.Service::receive()", "OVERRIDES", "fn:demo.ChildGateway::receive()");
            assertThat(relationships.findOutgoingRelationships(
                    SEMANTIC_PROJECT, scoped(SEMANTIC_PROJECT, "fn:demo.Unrelated::send(String)"), "OVERRIDES"))
                .extracting(relationship -> relationship.getToNodeId())
                .doesNotContain(scoped(SEMANTIC_PROJECT, "fn:demo.Gateway::send(String)"));
        }
    }

    private void assertEdge(
            CodeRelationshipRepository repository,
            String project,
            String from,
            String type,
            String to) {
        assertThat(repository.findOutgoingRelationships(project, scoped(project, from), type))
            .as("Kotlin persisted edge %s -[%s]-> %s", from, type, to)
            .extracting(relationship -> relationship.getToNodeId())
            .contains(scoped(project, to));
    }

    private String scoped(String project, String id) {
        return project + "::" + id;
    }
}
