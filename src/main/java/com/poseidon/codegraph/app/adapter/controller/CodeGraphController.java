package com.poseidon.codegraph.app.adapter.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poseidon.codegraph.app.adapter.dto.ApiResponse;
import com.poseidon.codegraph.app.adapter.dto.CreateFileNodesRequest;
import com.poseidon.codegraph.app.config.RepositoryConfigStore;
import com.poseidon.codegraph.model.delta.GraphDelta;
import com.poseidon.codegraph.starter.service.IncrementalUpdateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码图谱 Controller
 * 提供 REST API 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/code-graph")
public class CodeGraphController {
    
    private final IncrementalUpdateService incrementalUpdateService;
    private final GraphDeltaImportMapper graphDeltaImportMapper;
    private final RepositoryConfigStore repositoryStore;
    
    @Autowired
    public CodeGraphController(IncrementalUpdateService incrementalUpdateService, ObjectMapper objectMapper, RepositoryConfigStore repositoryStore) {
        this.incrementalUpdateService = incrementalUpdateService;
        this.graphDeltaImportMapper = new GraphDeltaImportMapper(objectMapper);
        this.repositoryStore = repositoryStore;
    }

    /**
     * 直接导入解析器产出的 GraphDelta。
     *
     * <p>适合外部 parser/CLI 已经生成 graph-delta.json 的场景。也兼容 JS parser
     * 默认输出的裸 graph JSON，导入时会补齐 scope 和 endpointKind。
     */
    @PostMapping("/delta")
    public ApiResponse<Void> applyGraphDelta(@RequestBody JsonNode payload) {
        try {
            GraphDelta delta = graphDeltaImportMapper.toGraphDelta(payload);
            String projectName = delta != null && delta.scope() != null ? delta.scope().projectName() : null;
            log.info("导入 GraphDelta 请求: projectName={}", projectName);
            incrementalUpdateService.applyGraphDelta(delta);
            return ApiResponse.success("GraphDelta 导入成功", null);
        } catch (Exception e) {
            log.error("导入 GraphDelta 失败", e);
            return ApiResponse.error("导入 GraphDelta 失败: " + detailedMessage(e));
        }
    }

    /**
     * 创建文件的所有节点
     * 首次解析指定文件，写入代码节点和调用关系
     *
     * @param request 创建文件节点请求
     * @return API 响应
     */
    @PostMapping("/files/nodes")
    public ApiResponse<Void> createFileNodes(@RequestBody CreateFileNodesRequest request) {
        try {
            resolveScope(request);
            log.info("创建文件节点请求: projectName={}, absoluteFile={}, projectFile={}",
                request.getProjectName(), request.getAbsoluteFilePath(), request.getProjectFilePath());

            ApiResponse<Void> validation = validateParseRequest(request);
            if (validation != null) {
                return validation;
            }

            incrementalUpdateService.handleFileAdded(
                request.getProjectName(),
                request.getAbsoluteFilePath(),
                request.getProjectFilePath(),
                request.getGitRepoUrl(),
                request.getGitBranch(),
                classpathEntries(request),
                sourcepathEntries(request),
                endpointRuleSources(request),
                request.getTraceRuleSources()
            );

            log.info("文件节点创建成功: {}", request.getProjectFilePath());
            return ApiResponse.success("文件节点创建成功", null);
        } catch (Exception e) {
            log.error("创建文件节点失败: {}", request.getProjectFilePath(), e);
            return ApiResponse.error("创建文件节点失败: " + detailedMessage(e));
        }
    }
    
    /**
     * 更新文件的所有节点
     * 重新解析指定文件，更新所有代码节点和调用关系
     * 
     * @param request 创建文件节点请求（复用）
     * @return API 响应
     */
    @PutMapping("/files/nodes")
    public ApiResponse<Void> updateFileNodes(@RequestBody CreateFileNodesRequest request) {
        try {
            resolveScope(request);
            log.info("更新文件节点请求: projectName={}, absoluteFile={}, projectFile={}", 
                request.getProjectName(), request.getAbsoluteFilePath(), request.getProjectFilePath());
            
            ApiResponse<Void> validation = validateParseRequest(request);
            if (validation != null) {
                return validation;
            }
            
            // 调用服务处理文件修改
            incrementalUpdateService.handleFileModified(
                request.getProjectName(),
                request.getAbsoluteFilePath(),
                request.getProjectFilePath(),
                request.getGitRepoUrl(),
                request.getGitBranch(),
                classpathEntries(request),
                sourcepathEntries(request),
                endpointRuleSources(request),
                request.getTraceRuleSources()
            );
            
            log.info("文件节点更新成功: {}", request.getProjectFilePath());
            return ApiResponse.success("文件节点更新成功", null);
            
        } catch (Exception e) {
            log.error("更新文件节点失败: {}", request.getProjectFilePath(), e);
            return ApiResponse.error("更新文件节点失败: " + detailedMessage(e));
        }
    }
    
    /**
     * 删除文件的所有节点
     * 删除指定文件相关的所有代码节点和调用关系
     * 
     * @param request 创建文件节点请求（复用）
     * @return API 响应
     */
    @DeleteMapping("/files/nodes")
    public ApiResponse<Void> deleteFileNodes(@RequestBody CreateFileNodesRequest request) {
        try {
            resolveScope(request);
            log.info("删除文件节点请求: projectName={}, projectFile={}", 
                request.getProjectName(), request.getProjectFilePath());
            
            // 删除操作其实只需要 projectFilePath，但为了接口统一，可能传了 absoluteFilePath
            if (request.getProjectName() == null || request.getProjectName().trim().isEmpty()) {
                return ApiResponse.error(400, "项目名称不能为空");
            }
            if (request.getProjectFilePath() == null || request.getProjectFilePath().trim().isEmpty()) {
                return ApiResponse.error(400, "项目相对路径不能为空");
            }
            
            // 调用服务处理文件删除
            incrementalUpdateService.handleFileDeleted(
                request.getProjectName(),
                request.getAbsoluteFilePath(), // 可能为 null，视情况而定
                request.getProjectFilePath(),
                request.getGitRepoUrl(),
                request.getGitBranch(),
                classpathEntries(request),
                sourcepathEntries(request)
            );
            
            log.info("文件节点删除成功: {}", request.getProjectFilePath());
            return ApiResponse.success("文件节点删除成功", null);
            
        } catch (Exception e) {
            log.error("删除文件节点失败: {}", request.getProjectFilePath(), e);
            return ApiResponse.error("删除文件节点失败: " + detailedMessage(e));
        }
    }
    
    /**
     * 健康检查接口
     */
    @GetMapping("/health-check")
    public ApiResponse<String> health() {
        return ApiResponse.success("服务运行正常", "OK");
    }

    private void resolveScope(CreateFileNodesRequest request) {
        if (request.getRepositoryId() == null) return;
        var repository = repositoryStore.findById(request.getRepositoryId())
            .orElseThrow(() -> new IllegalArgumentException("仓库不存在"));
        String branch = request.getGitBranch() == null ? repository.gitBranch() : request.getGitBranch();
        request.setProjectName(repositoryStore.identity(repository.id()).graphScope());
        request.setGitRepoUrl(repository.gitRepoUrl());
        request.setGitBranch(branch);
    }

    private ApiResponse<Void> validateParseRequest(CreateFileNodesRequest request) {
        if (request.getProjectName() == null || request.getProjectName().trim().isEmpty()) {
            return ApiResponse.error(400, "项目名称不能为空");
        }
        if (request.getAbsoluteFilePath() == null || request.getAbsoluteFilePath().trim().isEmpty()) {
            return ApiResponse.error(400, "文件绝对路径不能为空");
        }
        if (request.getProjectFilePath() == null || request.getProjectFilePath().trim().isEmpty()) {
            return ApiResponse.error(400, "项目相对路径不能为空");
        }
        return null;
    }

    private String[] classpathEntries(CreateFileNodesRequest request) {
        return request.getClasspathEntries() != null
            ? request.getClasspathEntries().toArray(new String[0])
            : new String[0];
    }

    private String[] sourcepathEntries(CreateFileNodesRequest request) {
        return request.getSourcepathEntries() != null
            ? request.getSourcepathEntries().toArray(new String[0])
            : new String[0];
    }

    private List<String> endpointRuleSources(CreateFileNodesRequest request) {
        List<String> sources = new ArrayList<>();
        if (request.getSerRuleSources() != null) {
            sources.addAll(request.getSerRuleSources());
        }
        if (request.getEndpointRuleSources() != null) {
            sources.addAll(request.getEndpointRuleSources());
        }
        return sources;
    }

    private String detailedMessage(Exception e) {
        StringBuilder message = new StringBuilder();
        Throwable current = e;
        while (current != null) {
            String currentMessage = current.getMessage();
            if (currentMessage != null && !currentMessage.isBlank()) {
                if (!message.isEmpty()) {
                    message.append(": ");
                }
                message.append(currentMessage);
            }
            current = current.getCause();
        }
        return message.isEmpty() ? e.getClass().getSimpleName() : message.toString();
    }
}
