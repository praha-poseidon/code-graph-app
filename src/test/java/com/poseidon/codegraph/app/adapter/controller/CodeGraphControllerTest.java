package com.poseidon.codegraph.app.adapter.controller;

import com.poseidon.codegraph.app.adapter.dto.ApiResponse;
import com.poseidon.codegraph.app.adapter.dto.CreateFileNodesRequest;
import com.poseidon.codegraph.starter.service.IncrementalUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CodeGraphControllerTest {

    private final IncrementalUpdateService service = mock(IncrementalUpdateService.class);
    private final com.poseidon.codegraph.app.config.RepositoryConfigStore repositories = mock(com.poseidon.codegraph.app.config.RepositoryConfigStore.class);
    private final CodeGraphController controller = new CodeGraphController(service, new ObjectMapper(), repositories);

    @Test
    void healthReturnsOk() {
        ApiResponse<String> response = controller.health();

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getMessage()).isEqualTo("服务运行正常");
        assertThat(response.getData()).isEqualTo("OK");
    }

    @Test
    void registeredRepositoryUsesSameServerScopeForAddUpdateAndDelete() {
        var repository = mock(com.poseidon.codegraph.app.config.RepositoryConfig.class);
        org.mockito.Mockito.when(repository.id()).thenReturn(7L);
        org.mockito.Mockito.when(repository.gitRepoUrl()).thenReturn("https://github.com/team/demo.git");
        org.mockito.Mockito.when(repository.gitBranch()).thenReturn("main");
        var identity = new com.poseidon.codegraph.app.config.RepositoryIdentity("uuid", "key", "github.com/team/demo");
        org.mockito.Mockito.when(repositories.findById(7L)).thenReturn(java.util.Optional.of(repository));
        org.mockito.Mockito.when(repositories.identity(7L)).thenReturn(identity);
        var request = request();
        request.setRepositoryId(7L);
        request.setProjectName("unsafe-short-name");
        request.setGitBranch(null);
        assertThat(controller.createFileNodes(request).getCode()).isEqualTo(200);
        assertThat(controller.updateFileNodes(request).getCode()).isEqualTo(200);
        assertThat(controller.deleteFileNodes(request).getCode()).isEqualTo(200);
        String scope = identity.graphScope();
        verify(service).handleFileAdded(eq(scope), any(), any(), eq("https://github.com/team/demo.git"), eq("main"), any(), any(), any(), any());
        verify(service).handleFileModified(eq(scope), any(), any(), eq("https://github.com/team/demo.git"), eq("main"), any(), any(), any(), any());
        verify(service).handleFileDeleted(eq(scope), any(), any(), eq("https://github.com/team/demo.git"), eq("main"), any(), any());
    }

    @Test
    void graphDeltaImportRejectsRemovedUiEndpointType() throws Exception {
        var payload = new ObjectMapper().readTree("""
            {
              "scope": {
                "projectName": "demo",
                "language": "typescript",
                "projectRoot": "/repo",
                "sourceFiles": ["src/App.tsx"],
                "changeType": "SOURCE_MODIFIED",
                "attributes": {}
              },
              "packages": [],
              "units": [],
              "functions": [],
              "endpoints": [{
                "endpointKind": "ui",
                "id": "ui:save",
                "language": "typescript",
                "projectName": "demo",
                "projectFilePath": "src/App.tsx",
                "endpointType": "UI"
              }],
              "relationships": [],
              "deletedNodeIds": [],
              "deletedRelationshipIds": [],
              "diagnostics": []
            }
            """);

        ApiResponse<Void> response = controller.applyGraphDelta(payload);

        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMessage()).contains("Could not resolve type id 'ui'");
        verifyNoInteractions(service);
    }

    @Test
    void updateRejectsMissingAbsoluteFilePath() {
        CreateFileNodesRequest request = request();
        request.setAbsoluteFilePath(" ");

        ApiResponse<Void> response = controller.updateFileNodes(request);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).isEqualTo("文件绝对路径不能为空");
        verifyNoInteractions(service);
    }

    @Test
    void updateRejectsMissingProjectName() {
        CreateFileNodesRequest request = request();
        request.setProjectName(" ");

        ApiResponse<Void> response = controller.updateFileNodes(request);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).isEqualTo("项目名称不能为空");
        verifyNoInteractions(service);
    }

    @Test
    void updateRejectsMissingProjectFilePath() {
        CreateFileNodesRequest request = request();
        request.setProjectFilePath(null);

        ApiResponse<Void> response = controller.updateFileNodes(request);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).isEqualTo("项目相对路径不能为空");
        verifyNoInteractions(service);
    }

    @Test
    void updateDelegatesToServiceAndConvertsPathLists() {
        CreateFileNodesRequest request = request();

        ApiResponse<Void> response = controller.updateFileNodes(request);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getMessage()).isEqualTo("文件节点更新成功");
        verify(service).handleFileModified(
            eq("demo"),
            eq("/repo/src/App.java"),
            eq("src/App.java"),
            eq("git@example/demo.git"),
            eq("main"),
            eq(new String[] {"classes"}),
            eq(new String[] {"src/main/java"}),
            eq(List.of("endpoint rule")),
            eq(List.of("trace rule")));
    }

    @Test
    void createDelegatesToServiceAndPassesExternalRules() {
        CreateFileNodesRequest request = request();

        ApiResponse<Void> response = controller.createFileNodes(request);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getMessage()).isEqualTo("文件节点创建成功");
        verify(service).handleFileAdded(
            eq("demo"),
            eq("/repo/src/App.java"),
            eq("src/App.java"),
            eq("git@example/demo.git"),
            eq("main"),
            eq(new String[] {"classes"}),
            eq(new String[] {"src/main/java"}),
            eq(List.of("endpoint rule")),
            eq(List.of("trace rule")));
    }

    @Test
    void createMergesUnifiedSerSourcesWithLegacyEndpointRuleSources() {
        CreateFileNodesRequest request = request();
        request.setSerRuleSources(List.of("combined ser"));

        ApiResponse<Void> response = controller.createFileNodes(request);

        assertThat(response.getCode()).isEqualTo(200);
        verify(service).handleFileAdded(
            eq("demo"),
            eq("/repo/src/App.java"),
            eq("src/App.java"),
            eq("git@example/demo.git"),
            eq("main"),
            eq(new String[] {"classes"}),
            eq(new String[] {"src/main/java"}),
            eq(List.of("combined ser", "endpoint rule")),
            eq(List.of("trace rule")));
    }

    @Test
    void createRejectsMissingProjectName() {
        CreateFileNodesRequest request = request();
        request.setProjectName("");

        ApiResponse<Void> response = controller.createFileNodes(request);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).isEqualTo("项目名称不能为空");
        verifyNoInteractions(service);
    }

    @Test
    void createReturnsErrorWhenServiceFails() {
        CreateFileNodesRequest request = request();
        doThrow(new RuntimeException("create failed")).when(service)
            .handleFileAdded(any(), any(), any(), any(), any(), any(), any(), any(), any());

        ApiResponse<Void> response = controller.createFileNodes(request);

        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMessage()).contains("创建文件节点失败: create failed");
    }

    @Test
    void updateReturnsErrorWhenServiceFails() {
        CreateFileNodesRequest request = request();
        doThrow(new RuntimeException("parse failed")).when(service)
            .handleFileModified(any(), any(), any(), any(), any(), any(), any(), any(), any());

        ApiResponse<Void> response = controller.updateFileNodes(request);

        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMessage()).contains("更新文件节点失败: parse failed");
    }

    @Test
    void deleteRejectsMissingProjectFilePath() {
        CreateFileNodesRequest request = request();
        request.setProjectFilePath(" ");

        ApiResponse<Void> response = controller.deleteFileNodes(request);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).isEqualTo("项目相对路径不能为空");
        verifyNoInteractions(service);
    }

    @Test
    void deleteRejectsMissingProjectName() {
        CreateFileNodesRequest request = request();
        request.setProjectName(null);

        ApiResponse<Void> response = controller.deleteFileNodes(request);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).isEqualTo("项目名称不能为空");
        verifyNoInteractions(service);
    }

    @Test
    void deleteDelegatesToServiceWithEmptyPathArraysWhenListsAreNull() {
        CreateFileNodesRequest request = request();
        request.setClasspathEntries(null);
        request.setSourcepathEntries(null);

        ApiResponse<Void> response = controller.deleteFileNodes(request);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getMessage()).isEqualTo("文件节点删除成功");
        verify(service).handleFileDeleted(
            eq("demo"),
            eq("/repo/src/App.java"),
            eq("src/App.java"),
            eq("git@example/demo.git"),
            eq("main"),
            eq(new String[] {}),
            eq(new String[] {}));
    }

    @Test
    void deleteReturnsErrorWhenServiceFails() {
        CreateFileNodesRequest request = request();
        doThrow(new RuntimeException("delete failed")).when(service)
            .handleFileDeleted(any(), any(), any(), any(), any(), any(), any());

        ApiResponse<Void> response = controller.deleteFileNodes(request);

        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMessage()).contains("删除文件节点失败: delete failed");
    }

    @Test
    void apiResponseFactoriesSetExpectedFields() {
        assertThat(ApiResponse.success("data")).extracting("code", "message", "data")
            .containsExactly(200, "操作成功", "data");
        assertThat(ApiResponse.error("bad")).extracting("code", "message", "data")
            .containsExactly(500, "bad", null);
    }

    private CreateFileNodesRequest request() {
        CreateFileNodesRequest request = new CreateFileNodesRequest();
        request.setProjectName("demo");
        request.setAbsoluteFilePath("/repo/src/App.java");
        request.setProjectFilePath("src/App.java");
        request.setGitRepoUrl("git@example/demo.git");
        request.setGitBranch("main");
        request.setClasspathEntries(List.of("classes"));
        request.setSourcepathEntries(List.of("src/main/java"));
        request.setEndpointRuleSources(List.of("endpoint rule"));
        request.setTraceRuleSources(List.of("trace rule"));
        return request;
    }
}
