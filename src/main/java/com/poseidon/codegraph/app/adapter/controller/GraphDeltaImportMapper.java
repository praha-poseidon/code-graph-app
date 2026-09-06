package com.poseidon.codegraph.app.adapter.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.poseidon.codegraph.model.delta.GraphDelta;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Normalizes parser JSON accepted by the workbench import endpoint.
 */
final class GraphDeltaImportMapper {

    private final ObjectMapper objectMapper;

    GraphDeltaImportMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    GraphDelta toGraphDelta(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("导入文件不能为空");
        }
        if (!payload.isObject()) {
            throw new IllegalArgumentException("导入文件必须是 JSON object");
        }

        ObjectNode root = ((ObjectNode) payload).deepCopy();
        ObjectNode graph = graphObject(root);
        ensureScope(graph, root);
        ensureEndpointKinds(graph);
        return objectMapper.convertValue(graph, GraphDelta.class);
    }

    private ObjectNode graphObject(ObjectNode root) {
        JsonNode graph = root.get("graph");
        if (graph != null && graph.isObject()) {
            return ((ObjectNode) graph).deepCopy();
        }
        return root;
    }

    private void ensureScope(ObjectNode graph, ObjectNode root) {
        JsonNode scope = graph.get("scope");
        if (scope != null && scope.isObject() && hasText(scope.path("projectName").asText(null))) {
            return;
        }

        ObjectNode nextScope = graph.putObject("scope");
        nextScope.put("projectName", inferProjectName(graph, root));
        nextScope.put("language", inferLanguage(graph));
        nextScope.put("projectRoot", text(root.path("projectRoot"), text(root.path("root"), "")));
        nextScope.putArray("sourceFiles").addAll(sourceFiles(graph));
        nextScope.put("changeType", "SOURCE_MODIFIED");
        nextScope.putObject("attributes");
    }

    private void ensureEndpointKinds(ObjectNode graph) {
        JsonNode endpoints = graph.get("endpoints");
        if (endpoints == null || !endpoints.isArray()) {
            return;
        }
        for (JsonNode endpoint : endpoints) {
            if (!endpoint.isObject()) {
                continue;
            }
            ObjectNode object = (ObjectNode) endpoint;
            if (!hasText(object.path("endpointKind").asText(null))) {
                object.put("endpointKind", endpointKind(object.path("endpointType").asText(null)));
            }
        }
    }

    private String inferProjectName(ObjectNode graph, ObjectNode root) {
        String explicit = text(root.path("projectName"), text(root.path("name"), null));
        if (hasText(explicit)) {
            return explicit;
        }
        // Prefer explicit projectName fields on nodes (not directory package "name" like "src").
        String fromNode = firstText(graph, "projectName", "packages", "units", "functions", "endpoints");
        if (hasText(fromNode)) {
            return fromNode;
        }
        // Frontend unit/package ids look like: knowledgeforge#src/foo/bar.js or knowledgeforge#pkg#src/foo
        String fromId = projectNameFromNodeIds(graph);
        if (hasText(fromId)) {
            return fromId;
        }
        return "imported-graph";
    }

    /**
     * Infer project name from stable node ids: "{projectName}#..." / "{projectName}#pkg#...".
     * Do not use package {@code name} (often a directory basename such as "src").
     */
    private String projectNameFromNodeIds(ObjectNode graph) {
        for (String array : new String[] {"units", "packages", "functions", "endpoints"}) {
            JsonNode nodes = graph.path(array);
            if (!nodes.isArray()) {
                continue;
            }
            for (JsonNode node : nodes) {
                String id = text(node.path("id"), null);
                if (!hasText(id)) {
                    continue;
                }
                int hash = id.indexOf('#');
                if (hash > 0) {
                    return id.substring(0, hash);
                }
            }
        }
        return null;
    }

    private String inferLanguage(ObjectNode graph) {
        String language = firstText(graph, "language", "units", "functions", "endpoints", "packages");
        if (hasText(language) && !"unknown".equalsIgnoreCase(language)) {
            return language;
        }
        return "unknown";
    }

    private ArrayNode sourceFiles(ObjectNode graph) {
        ArrayNode files = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        addProjectFiles(seen, graph.path("units"));
        addProjectFiles(seen, graph.path("functions"));
        addProjectFiles(seen, graph.path("endpoints"));
        for (String file : seen) {
            files.add(file);
        }
        return files;
    }

    private void addProjectFiles(Set<String> files, JsonNode nodes) {
        if (!nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String projectFilePath = text(node.path("projectFilePath"), null);
            if (hasText(projectFilePath)) {
                files.add(projectFilePath);
            }
        }
    }

    private String firstText(ObjectNode graph, String field, String... arrays) {
        for (String array : arrays) {
            JsonNode nodes = graph.path(array);
            if (!nodes.isArray()) {
                continue;
            }
            for (JsonNode node : nodes) {
                String value = text(node.path(field), null);
                if (hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String endpointKind(String endpointType) {
        if (!hasText(endpointType)) {
            return "http";
        }
        return switch (endpointType.toUpperCase(Locale.ROOT)) {
            case "MQ" -> "mq";
            case "REDIS" -> "redis";
            case "DB" -> "db";
            default -> "http";
        };
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.asText(null);
        return hasText(value) ? value : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
