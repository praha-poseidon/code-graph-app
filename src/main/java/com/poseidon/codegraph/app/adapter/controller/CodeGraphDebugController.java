package com.poseidon.codegraph.app.adapter.controller;

import com.poseidon.codegraph.app.adapter.dto.ApiResponse;
import com.poseidon.codegraph.engine.application.model.CodeEndpointDO;
import com.poseidon.codegraph.engine.application.model.CodeFunctionDO;
import com.poseidon.codegraph.engine.application.model.CodePackageDO;
import com.poseidon.codegraph.engine.application.model.CodeRelationshipDO;
import com.poseidon.codegraph.engine.application.model.CodeUnitDO;
import com.poseidon.codegraph.storage.memory.repository.InMemoryCodeGraphRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only local debug APIs for memory storage demos.
 */
@RestController
@RequestMapping("/api/code-graph/debug")
@ConditionalOnProperty(name = "code-graph.storage.type", havingValue = "memory", matchIfMissing = true)
public class CodeGraphDebugController {

    private final InMemoryCodeGraphRepository repository;

    public CodeGraphDebugController(InMemoryCodeGraphRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/projects/{projectName}/graph")
    public ApiResponse<GraphSnapshot> graph(@PathVariable String projectName) {
        return ApiResponse.success(snapshot(projectName));
    }

    @GetMapping("/projects/{projectName}/nodes")
    public ApiResponse<NodeSnapshot> nodes(@PathVariable String projectName) {
        return ApiResponse.success(nodesOnly(projectName));
    }

    @GetMapping("/projects/{projectName}/relationships")
    public ApiResponse<List<CodeRelationshipDO>> relationships(@PathVariable String projectName) {
        return ApiResponse.success(repository.findRelationshipsByProject(projectName));
    }

    @GetMapping("/projects/{projectName}/endpoints")
    public ApiResponse<List<CodeEndpointDO>> endpoints(@PathVariable String projectName) {
        return ApiResponse.success(repository.findEndpointsByProject(projectName));
    }

    private GraphSnapshot snapshot(String projectName) {
        NodeSnapshot nodes = nodesOnly(projectName);
        List<CodeRelationshipDO> relationships = repository.findRelationshipsByProject(projectName);
        return new GraphSnapshot(
            projectName,
            new GraphCounts(
                nodes.packages().size(),
                nodes.units().size(),
                nodes.functions().size(),
                nodes.endpoints().size(),
                relationships.size()
            ),
            nodes.packages(),
            nodes.units(),
            nodes.functions(),
            nodes.endpoints(),
            relationships
        );
    }

    private NodeSnapshot nodesOnly(String projectName) {
        return new NodeSnapshot(
            projectName,
            repository.findPackagesByProject(projectName),
            repository.findUnitsByProject(projectName),
            repository.findFunctionsByProject(projectName),
            repository.findEndpointsByProject(projectName)
        );
    }

    public record GraphSnapshot(
        String projectName,
        GraphCounts counts,
        List<CodePackageDO> packages,
        List<CodeUnitDO> units,
        List<CodeFunctionDO> functions,
        List<CodeEndpointDO> endpoints,
        List<CodeRelationshipDO> relationships) {
    }

    public record NodeSnapshot(
        String projectName,
        List<CodePackageDO> packages,
        List<CodeUnitDO> units,
        List<CodeFunctionDO> functions,
        List<CodeEndpointDO> endpoints) {
    }

    public record GraphCounts(
        int packages,
        int units,
        int functions,
        int endpoints,
        int relationships) {
    }
}
