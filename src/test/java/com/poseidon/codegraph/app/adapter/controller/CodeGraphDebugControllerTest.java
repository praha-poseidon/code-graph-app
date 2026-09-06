package com.poseidon.codegraph.app.adapter.controller;

import com.poseidon.codegraph.app.adapter.dto.ApiResponse;
import com.poseidon.codegraph.engine.application.model.CodeEndpointDO;
import com.poseidon.codegraph.engine.application.model.CodeFunctionDO;
import com.poseidon.codegraph.engine.application.model.CodePackageDO;
import com.poseidon.codegraph.engine.application.model.CodeRelationshipDO;
import com.poseidon.codegraph.engine.application.model.CodeUnitDO;
import com.poseidon.codegraph.storage.memory.repository.InMemoryCodeGraphRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphDebugControllerTest {

    private final InMemoryCodeGraphRepository repository = new InMemoryCodeGraphRepository();
    private final CodeGraphDebugController controller = new CodeGraphDebugController(repository);

    @Test
    void graphReturnsProjectSnapshotFromMemoryStorage() {
        seed("demo");
        seed("other");

        ApiResponse<CodeGraphDebugController.GraphSnapshot> response = controller.graph("demo");

        assertThat(response.getCode()).isEqualTo(200);
        CodeGraphDebugController.GraphSnapshot graph = response.getData();
        assertThat(graph.projectName()).isEqualTo("demo");
        assertThat(graph.counts().packages()).isEqualTo(1);
        assertThat(graph.counts().units()).isEqualTo(1);
        assertThat(graph.counts().functions()).isEqualTo(1);
        assertThat(graph.counts().endpoints()).isEqualTo(1);
        assertThat(graph.counts().relationships()).isEqualTo(1);
        assertThat(graph.units()).extracting(CodeUnitDO::getId).containsExactly("demo::unit:UserPage");
        assertThat(graph.relationships()).extracting(CodeRelationshipDO::getId).containsExactly("demo::rel:unit-function");
    }

    @Test
    void nodesRelationshipsAndEndpointsReturnSeparateViews() {
        seed("demo");

        assertThat(controller.nodes("demo").getData().functions())
            .extracting(CodeFunctionDO::getId)
            .containsExactly("demo::fn:UserPage()");
        assertThat(controller.relationships("demo").getData())
            .extracting(CodeRelationshipDO::getRelationshipType)
            .containsExactly("UNIT_TO_FUNCTION");
        assertThat(controller.endpoints("demo").getData())
            .extracting(CodeEndpointDO::getMatchIdentity)
            .containsExactly("HTTP:GET:/api/users");
    }

    private void seed(String projectName) {
        CodePackageDO pkg = new CodePackageDO();
        pkg.setId(projectName + "::pkg:.");
        pkg.setName(projectName);
        pkg.setQualifiedName(projectName);
        pkg.setProjectName(projectName);
        pkg.setLanguage("typescript");
        repository.insertPackagesBatch(List.of(pkg));

        CodeUnitDO unit = new CodeUnitDO();
        unit.setId(projectName + "::unit:UserPage");
        unit.setName("UserPage");
        unit.setQualifiedName("UserPage");
        unit.setProjectName(projectName);
        unit.setLanguage("typescript");
        unit.setProjectFilePath("src/pages/UserPage.tsx");
        repository.insertUnitsBatch(List.of(unit));

        CodeFunctionDO function = new CodeFunctionDO();
        function.setId(projectName + "::fn:UserPage()");
        function.setName("UserPage");
        function.setQualifiedName("UserPage()");
        function.setProjectName(projectName);
        function.setLanguage("typescript");
        function.setProjectFilePath("src/pages/UserPage.tsx");
        repository.insertFunctionsBatch(List.of(function));

        CodeEndpointDO endpoint = new CodeEndpointDO();
        endpoint.setId(projectName + "::endpoint:users");
        endpoint.setName("HTTP:GET:/api/users");
        endpoint.setQualifiedName("HTTP:GET:/api/users");
        endpoint.setProjectName(projectName);
        endpoint.setLanguage("typescript");
        endpoint.setProjectFilePath("src/api/user.ts");
        endpoint.setEndpointType("HTTP");
        endpoint.setDirection("outbound");
        endpoint.setMatchIdentity("HTTP:GET:/api/users");
        repository.insertEndpointsBatch(List.of(endpoint));

        CodeRelationshipDO relationship = new CodeRelationshipDO();
        relationship.setId(projectName + "::rel:unit-function");
        relationship.setFromNodeId(unit.getId());
        relationship.setToNodeId(function.getId());
        relationship.setProjectName(projectName);
        relationship.setLanguage("typescript");
        relationship.setRelationshipType("UNIT_TO_FUNCTION");
        repository.insertRelationshipsBatch(List.of(relationship));
    }
}
