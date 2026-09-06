package com.poseidon.codegraph.app;

import com.poseidon.codegraph.engine.application.model.CodeEndpointDO;
import com.poseidon.codegraph.model.RelationshipType;
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

@EnabledIfEnvironmentVariable(named = "FRONTEND_CODE_GRAPH_CLI", matches = ".+")
class FrontendProcessParserEndToEndTest {

    private static final String PROJECT = "frontend-process-e2e";
    private static final String TYPE_PROJECT = "frontend-type-relations-e2e";
    private static final String REDUX_PROJECT = "frontend-redux-dispatch-e2e";
    private static final String FETCH_RULE = """
        rule "Frontend E2E Fetch"
        fact frontend_api_call

        find call fetch

        let path =
          from argument[0] take value

        let method =
          from argument[1] take attr(method)
        fallback "GET"

        build {
          client: "fetch"
          method: method | normalize upper
          path: path | normalize httpPath
        }
        """;
    private static final String REQUEST_RULE = """
        rule "Frontend E2E Request Methods"
        fact frontend_api_call

        find call [get,post,put,patch,delete,del]
        when call owner request

        let method =
          from call take method

        let path =
          from argument[0] take value

        build {
          client: "request"
          method: method | normalize upper
          path: path | normalize httpPath
        }
        """;

    @BeforeAll
    static void configureExternalParser() {
        System.setProperty("codegraph.parser.process.languages", "typescript javascript");
        System.setProperty("codegraph.parser.process.typescript.command",
            "node '" + System.getenv("FRONTEND_CODE_GRAPH_CLI") + "' --stdio-stream");
        System.setProperty("codegraph.parser.process.javascript.command",
            "node '" + System.getenv("FRONTEND_CODE_GRAPH_CLI") + "' --stdio-stream");
        System.setProperty("codegraph.parser.process.typescript.streaming", "true");
        System.setProperty("codegraph.parser.process.javascript.streaming", "true");
        System.setProperty("codegraph.parser.process.timeoutSeconds", "30");
    }

    @AfterAll
    static void clearExternalParserConfig() {
        System.clearProperty("codegraph.parser.process.languages");
        System.clearProperty("codegraph.parser.process.typescript.command");
        System.clearProperty("codegraph.parser.process.javascript.command");
        System.clearProperty("codegraph.parser.process.typescript.streaming");
        System.clearProperty("codegraph.parser.process.javascript.streaming");
        System.clearProperty("codegraph.parser.process.timeoutSeconds");
    }

    @Test
    void appServiceWritesFrontendGraphFromExternalProcessParser() {
        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        IncrementalUpdateService service = new IncrementalUpdateService(
            repository,
            repository,
            repository,
            repository,
            repository
        );
        Path projectRoot = frontendParserRoot().resolve("fixtures/react-basic");
        add(service, PROJECT, projectRoot, "src/api/user.ts");
        add(service, PROJECT, projectRoot, "src/components/UserCard.jsx");
        add(service, PROJECT, projectRoot, "src/hooks/useUser.ts");
        add(service, PROJECT, projectRoot, "src/pages/UserPage.tsx");

        assertThat(repository.findEndpointsByProjectFilePath(PROJECT, "src/api/user.ts"))
            .extracting(CodeEndpointDO::getMatchIdentity)
            .contains("HTTP:GET:/api/users/{param}", "HTTP:POST:/api/users");

        assertThat(repository.findOutgoingRelationships(
                PROJECT,
                PROJECT + "::src/pages/UserPage.tsx",
                RelationshipType.UNIT_TO_FUNCTION.name()))
            .extracting(relationship -> relationship.getToNodeId())
            .contains(PROJECT + "::src/pages/UserPage.tsx::UserPage()");
        assertThat(repository.findOutgoingRelationships(
                PROJECT,
                PROJECT + "::src/pages/UserPage.tsx::UserPage()",
                RelationshipType.CALLS.name()))
            .hasSize(1);
        assertThat(repository.findOutgoingRelationships(
                PROJECT,
                PROJECT + "::src/api/user.ts::createUser(payload: unknown)",
                RelationshipType.FUNCTION_TO_ENDPOINT.name()))
            .hasSize(1);
    }

    @Test
    void appServicePersistsExactTypescriptTypeAndCallRelationships() {
        InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
        IncrementalUpdateService service = new IncrementalUpdateService(
            repository,
            repository,
            repository,
            repository,
            repository
        );
        Path projectRoot = frontendParserRoot().resolve("fixtures/type-relations");
        try (IncrementalUpdateSession session = service.openSession("typescript")) {
            add(session, TYPE_PROJECT, projectRoot, "src/contracts.ts");
            add(session, TYPE_PROJECT, projectRoot, "src/other.ts");
            add(session, TYPE_PROJECT, projectRoot, "src/service.ts");
            add(session, TYPE_PROJECT, projectRoot, "src/page.ts");
        }

        String userService = TYPE_PROJECT + "::src/service.ts::UserService";
        String baseService = TYPE_PROJECT + "::src/contracts.ts::BaseService";
        String apiClient = TYPE_PROJECT + "::src/contracts.ts::ApiClient";
        String wrongApiClient = TYPE_PROJECT + "::src/other.ts::ApiClient";
        String save = TYPE_PROJECT + "::src/service.ts::UserService.save()";
        String interfaceSave = TYPE_PROJECT + "::src/contracts.ts::ApiClient.save()";
        String handleSave = TYPE_PROJECT + "::src/page.ts::handleSave()";

        assertThat(repository.findOutgoingRelationships(
                TYPE_PROJECT, userService, "EXTENDS"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(baseService);
        assertThat(repository.findOutgoingRelationships(
                TYPE_PROJECT, userService, "IMPLEMENTS"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(apiClient)
            .doesNotContain(wrongApiClient);
        assertThat(repository.findOutgoingRelationships(
                TYPE_PROJECT, save, "OVERRIDES"))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(interfaceSave);
        assertThat(repository.findOutgoingRelationships(
                TYPE_PROJECT, handleSave, RelationshipType.CALLS.name()))
            .extracting(relationship -> relationship.getToNodeId())
            .containsExactly(save);
    }

    /**
     * Source oracle: UserService extends BaseService, implements the contracts.ApiClient
     * (not the same-named type in other.ts), overrides save, and handleSave calls it.
     * Read-back is from Neo4j after the real parser and Engine storage path.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "NEO4J_URI", matches = ".+")
    void typescriptNativeRelationshipsMatchPersistedNeo4jEdges() {
        String uri = System.getenv("NEO4J_URI");
        String username = environmentOrDefault("NEO4J_USERNAME", "neo4j");
        String password = environmentOrDefault("NEO4J_PASSWORD", "password");

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
            driver.verifyConnectivity();
            deleteProject(driver, TYPE_PROJECT);

            Neo4jCodePackageRepository packages = new Neo4jCodePackageRepository(driver);
            Neo4jCodeUnitRepository units = new Neo4jCodeUnitRepository(driver);
            Neo4jCodeFunctionRepository functions = new Neo4jCodeFunctionRepository(driver);
            Neo4jCodeRelationshipRepository relationships = new Neo4jCodeRelationshipRepository(driver);
            Neo4jCodeEndpointRepository endpoints = new Neo4jCodeEndpointRepository(driver);
            IncrementalUpdateService service = new IncrementalUpdateService(
                packages, units, functions, relationships, endpoints
            );

            Path projectRoot = frontendParserRoot().resolve("fixtures/type-relations");
            try (IncrementalUpdateSession session = service.openSession("typescript")) {
                add(session, TYPE_PROJECT, projectRoot, "src/contracts.ts");
                add(session, TYPE_PROJECT, projectRoot, "src/other.ts");
                add(session, TYPE_PROJECT, projectRoot, "src/service.ts");
                add(session, TYPE_PROJECT, projectRoot, "src/page.ts");
            }

            String userService = TYPE_PROJECT + "::src/service.ts::UserService";
            String baseService = TYPE_PROJECT + "::src/contracts.ts::BaseService";
            String apiClient = TYPE_PROJECT + "::src/contracts.ts::ApiClient";
            String wrongApiClient = TYPE_PROJECT + "::src/other.ts::ApiClient";
            String save = TYPE_PROJECT + "::src/service.ts::UserService.save()";
            String interfaceSave = TYPE_PROJECT + "::src/contracts.ts::ApiClient.save()";
            String handleSave = TYPE_PROJECT + "::src/page.ts::handleSave()";

            assertThat(relationships.findOutgoingRelationships(TYPE_PROJECT, userService, "EXTENDS"))
                .extracting(relationship -> relationship.getToNodeId())
                .containsExactly(baseService);
            assertThat(relationships.findOutgoingRelationships(TYPE_PROJECT, userService, "IMPLEMENTS"))
                .extracting(relationship -> relationship.getToNodeId())
                .containsExactly(apiClient)
                .doesNotContain(wrongApiClient);
            assertThat(relationships.findOutgoingRelationships(TYPE_PROJECT, save, "OVERRIDES"))
                .extracting(relationship -> relationship.getToNodeId())
                .containsExactly(interfaceSave);
            assertThat(relationships.findOutgoingRelationships(
                    TYPE_PROJECT, handleSave, RelationshipType.CALLS.name()))
                .extracting(relationship -> relationship.getToNodeId())
                .containsExactly(save);
        } finally {
            try (Driver cleanup = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
                deleteProject(cleanup, TYPE_PROJECT);
            }
        }
    }

    /**
     * Source oracle: model.ts declares user.effects.save/cancel; page.tsx maps them through
     * connect/mapDispatch and calls the injected props through conditional expressions.
     * Evidence is read back from Neo4j after the real parser process and Engine apply path.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "NEO4J_URI", matches = ".+")
    void reduxDispatchCallsMatchPersistedNeo4jEdges() {
        String uri = System.getenv("NEO4J_URI");
        String username = environmentOrDefault("NEO4J_USERNAME", "neo4j");
        String password = environmentOrDefault("NEO4J_PASSWORD", "password");

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
            driver.verifyConnectivity();
            deleteProject(driver, REDUX_PROJECT);

            Neo4jCodePackageRepository packages = new Neo4jCodePackageRepository(driver);
            Neo4jCodeUnitRepository units = new Neo4jCodeUnitRepository(driver);
            Neo4jCodeFunctionRepository functions = new Neo4jCodeFunctionRepository(driver);
            Neo4jCodeRelationshipRepository relationships = new Neo4jCodeRelationshipRepository(driver);
            Neo4jCodeEndpointRepository endpoints = new Neo4jCodeEndpointRepository(driver);
            IncrementalUpdateService service = new IncrementalUpdateService(
                packages, units, functions, relationships, endpoints
            );

            Path projectRoot = frontendParserRoot().resolve("fixtures/redux-dispatch");
            add(service, REDUX_PROJECT, projectRoot, "src/model.ts");
            add(service, REDUX_PROJECT, projectRoot, "src/page.tsx");

            String page = REDUX_PROJECT + "::src/page.tsx::Page({ save, cancel: onCancel }: Props)";
            String propsPage = REDUX_PROJECT + "::src/page.tsx::PropsPage(props: Props)";
            String dynamic = REDUX_PROJECT + "::src/page.tsx::dynamic(dispatch: any,model: string,effect: string)";
            String save = REDUX_PROJECT + "::src/model.ts::user.effects.save(payload: unknown)";
            String cancel = REDUX_PROJECT + "::src/model.ts::user.effects.cancel()";

            assertThat(relationships.findOutgoingRelationships(
                    REDUX_PROJECT, page, RelationshipType.CALLS.name()))
                .extracting(relationship -> relationship.getToNodeId())
                .containsExactlyInAnyOrder(save, cancel);
            assertThat(relationships.findOutgoingRelationships(
                    REDUX_PROJECT, propsPage, RelationshipType.CALLS.name()))
                .extracting(relationship -> relationship.getToNodeId())
                .containsExactlyInAnyOrder(save, cancel);
            assertThat(relationships.findOutgoingRelationships(
                    REDUX_PROJECT, dynamic, RelationshipType.CALLS.name()))
                .extracting(relationship -> relationship.getToNodeId())
                .doesNotContain(save, cancel);
        } finally {
            try (Driver cleanup = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
                deleteProject(cleanup, REDUX_PROJECT);
            }
        }
    }

    private static void add(
            IncrementalUpdateService service,
            String projectName,
            Path projectRoot,
            String projectFilePath) {
        service.handleFileAdded(
            projectName,
            projectRoot.resolve(projectFilePath).toString(),
            projectFilePath,
            "git@example/" + projectName + ".git",
            "main",
            new String[0],
            new String[] { projectRoot.resolve("src").toString() },
            List.of(FETCH_RULE, REQUEST_RULE),
            List.of()
        );
    }

    private static void add(
            IncrementalUpdateSession session,
            String projectName,
            Path projectRoot,
            String projectFilePath) {
        session.handleFileAdded(
            projectName,
            projectRoot.resolve(projectFilePath).toString(),
            projectFilePath,
            "git@example/" + projectName + ".git",
            "main",
            new String[0],
            new String[] { projectRoot.resolve("src").toString() },
            List.of(FETCH_RULE, REQUEST_RULE),
            List.of()
        );
    }

    private static Path frontendParserRoot() {
        Path cli = Path.of(System.getenv("FRONTEND_CODE_GRAPH_CLI")).toAbsolutePath();
        return cli.getParent().getParent();
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
}
