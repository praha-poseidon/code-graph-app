package com.poseidon.codegraph.app.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poseidon.codegraph.app.config.RepositoryConfigStore;
import com.poseidon.codegraph.engine.application.model.CodeRelationshipDO;
import com.poseidon.codegraph.engine.application.repository.*;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/** Read-only tools use the same storage ports as Engine, never parser processes. */
@Component
public class GraphMcpTools {
    private final RepositoryConfigStore projects;
    private final CodeFunctionRepository functions;
    private final CodeUnitRepository units;
    private final CodeEndpointRepository endpoints;
    private final CodeRelationshipRepository relationships;
    private final ObjectMapper mapper;

    public GraphMcpTools(RepositoryConfigStore projects, CodeFunctionRepository functions,
            CodeUnitRepository units, CodeEndpointRepository endpoints,
            CodeRelationshipRepository relationships, ObjectMapper mapper) {
        this.projects = projects; this.functions = functions; this.units = units;
        this.endpoints = endpoints; this.relationships = relationships; this.mapper = mapper;
    }

    public List<SyncToolSpecification> tools() {
        return List.of(
            tool("list_projects", "List registered repositories and their current graph scopes; no credentials.",
                Map.of(), List.of(), args -> Map.of("projects", projects.findAll().stream().map(project -> {
                    var identity = projects.identity(project.id());
                    return Map.of("repositoryId", project.id(), "name", project.name(),
                        "projectId", identity.projectId(), "branch", project.gitBranch(),
                        "graphScope", identity.graphScope());
                }).toList())),
            tool("get_file_nodes", "Read persisted functions, types and endpoints for a repository-relative source file. Does not parse or build.",
                Map.of("repositoryId", integer(), "path", string()), List.of("repositoryId", "path"), args -> {
                    String scope = scope(args);
                    String path = required(args, "path");
                    if (path.startsWith("/") || path.contains("\\") || Arrays.asList(path.split("/")).contains(".."))
                        throw new IllegalArgumentException("path must be repository-relative");
                    List<Object> nodes = new ArrayList<>();
                    nodes.addAll(units.findUnitsByProjectFilePath(scope, path));
                    nodes.addAll(functions.findFunctionsByProjectFilePath(scope, path));
                    nodes.addAll(endpoints.findEndpointsByProjectFilePath(scope, path));
                    return Map.of("graphScope", scope, "nodes", nodes.stream().limit(500).toList(),
                        "truncated", nodes.size() > 500);
                }),
            tool("trace_relationships", "Read native stored relationships around a node, within the selected repository branch. No inferred/renamed edges. Bounded traversal; truncated=true means incomplete.",
                Map.of("repositoryId", integer(), "nodeId", string(),
                    "direction", Map.of("type", "string", "enum", List.of("OUT", "IN", "BOTH")),
                    "depth", Map.of("type", "integer", "minimum", 1, "maximum", 4)),
                List.of("repositoryId", "nodeId"), this::trace));
    }

    private Object trace(Map<String, Object> args) {
        String scope = scope(args), node = required(args, "nodeId");
        String direction = Objects.toString(args.getOrDefault("direction", "OUT"));
        if (!Set.of("OUT", "IN", "BOTH").contains(direction)) throw new IllegalArgumentException("Invalid direction");
        int depth = args.containsKey("depth") ? exactInteger(args.get("depth"), "depth") : 1;
        if (depth < 1 || depth > 4) throw new IllegalArgumentException("depth must be between 1 and 4");
        Set<String> visited = new HashSet<>();
        Set<String> frontier = new LinkedHashSet<>(List.of(node));
        Map<String, CodeRelationshipDO> edges = new LinkedHashMap<>();
        boolean truncated = false;
        outer: for (int level = 0; level < depth; level++) {
            Set<String> next = new LinkedHashSet<>();
            for (String current : frontier) {
                if (!visited.add(current)) continue;
                if (visited.size() > 200) { truncated = true; break outer; }
                List<CodeRelationshipDO> adjacent = new ArrayList<>();
                if (!direction.equals("IN")) adjacent.addAll(relationships.findOutgoingRelationships(scope, current, null));
                if (!direction.equals("OUT")) adjacent.addAll(relationships.findIncomingRelationships(scope, current, null));
                for (var edge : adjacent) {
                    // Fail closed if an adapter returns a relationship from another scope.
                    if (!scope.equals(edge.getProjectName())) continue;
                    String key = edge.getFromNodeId() + "\u0000" + edge.getRelationshipType() + "\u0000" + edge.getToNodeId();
                    if (!edges.containsKey(key) && edges.size() >= 500) { truncated = true; break outer; }
                    edges.putIfAbsent(key, edge);
                    if (!direction.equals("IN") && current.equals(edge.getFromNodeId())) next.add(edge.getToNodeId());
                    if (!direction.equals("OUT") && current.equals(edge.getToNodeId())) next.add(edge.getFromNodeId());
                }
            }
            frontier = next;
        }
        return Map.of("graphScope", scope, "relationships", edges.values(), "truncated", truncated);
    }

    private String scope(Map<String, Object> args) {
        int id = exactInteger(args.get("repositoryId"), "repositoryId");
        var project = projects.findById(id).orElseThrow(() -> new IllegalArgumentException("Repository not found"));
        return projects.identity(id).graphScope();
    }

    private static int exactInteger(Object value, String key) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1)
            throw new IllegalArgumentException(key + " must be a positive integer");
        return number.intValue();
    }

    private static String required(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof String value) || value.isBlank() || value.length() > 4096)
            throw new IllegalArgumentException(key + " must be a non-empty string (max 4096)");
        return value;
    }

    private SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
            List<String> required, Function<Map<String, Object>, Object> handler) {
        var tool = Tool.builder().name(name).description(description)
            .inputSchema(new JsonSchema("object", properties, required, false, null, null))
            .annotations(new ToolAnnotations(null, true, false, true, false, false)).build();
        return new SyncToolSpecification(tool, (context, request) -> {
            try {
                Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
                if (!properties.keySet().containsAll(args.keySet())) throw new IllegalArgumentException("Unknown argument");
                String json = mapper.writeValueAsString(handler.apply(args));
                if (json.length() > 1_000_000) throw new IllegalArgumentException("Result too large; select a smaller file or trace depth");
                return CallToolResult.builder().addTextContent(json).isError(false).build();
            } catch (IllegalArgumentException exception) {
                return CallToolResult.builder().addTextContent(exception.getMessage()).isError(true).build();
            } catch (Exception exception) {
                // Never expose database connection strings, SQL or credentials to MCP callers.
                return CallToolResult.builder().addTextContent("Graph query failed").isError(true).build();
            }
        });
    }
    private static Map<String, Object> string() { return Map.of("type", "string", "minLength", 1, "maxLength", 4096); }
    private static Map<String, Object> integer() { return Map.of("type", "integer", "minimum", 1); }
}
