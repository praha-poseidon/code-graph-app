package com.poseidon.codegraph.app.mcp;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GraphMcpInfoController {
    private final GraphMcpAccess access;
    public GraphMcpInfoController(GraphMcpAccess access) { this.access = access; }
    @GetMapping("/api/mcp")
    public Info info() { return new Info(access.enabled(), access.endpoint(), "streamable-http", access.authenticationRequired()); }
    public record Info(boolean enabled, String endpoint, String transport, boolean authenticationRequired) {}
}
