package com.poseidon.codegraph.app.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** Local-only without a token; deployment token is never returned to the browser. */
@Component
public class GraphMcpAccess extends OncePerRequestFilter {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");
    private final String token;
    private final URI publicUrl;
    private final boolean enabled;

    public GraphMcpAccess(@Value("${code-graph.mcp.token:}") String token,
            @Value("${code-graph.mcp.public-url:}") String publicUrl,
            @Value("${code-graph.mcp.enabled:true}") boolean enabled) {
        this.token = token; this.enabled = enabled;
        this.publicUrl = publicUrl.isBlank() ? null : URI.create(publicUrl);
        if (this.publicUrl != null && (!Set.of("http", "https").contains(this.publicUrl.getScheme())
                || this.publicUrl.getHost() == null || this.publicUrl.getUserInfo() != null
                || this.publicUrl.getQuery() != null || this.publicUrl.getFragment() != null
                || !this.publicUrl.getPath().endsWith("/mcp")))
            throw new IllegalArgumentException("MCP public URL must be an HTTP(S) endpoint ending in /mcp, without credentials");
        if (this.publicUrl != null && !LOOPBACK.contains(this.publicUrl.getHost()) && token.isBlank())
            throw new IllegalArgumentException("Remote MCP requires CODEGRAPH_MCP_TOKEN");
    }

    public boolean enabled() { return enabled; }
    public String endpoint() { return publicUrl == null ? "/mcp" : publicUrl.toString(); }
    public boolean authenticationRequired() { return !token.isBlank(); }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(request.getContextPath() + "/mcp");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!enabled) { response.sendError(404); return; }
        String host = request.getServerName();
        boolean allowedHost = LOOPBACK.contains(host) || publicUrl != null && host.equalsIgnoreCase(publicUrl.getHost());
        if (!allowedHost || !allowedOrigin(request.getHeader("Origin"))) { response.sendError(403); return; }
        if (token.isBlank()) {
            if (!LOOPBACK.contains(request.getRemoteAddr())) { response.sendError(403); return; }
        } else {
            String supplied = request.getHeader("Authorization");
            if (supplied == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setHeader("WWW-Authenticate", "Bearer"); response.sendError(401); return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean allowedOrigin(String value) {
        if (value == null) return true; // Native MCP clients do not send Origin.
        try {
            URI origin = URI.create(value);
            if (origin.getHost() == null || origin.getUserInfo() != null || origin.getQuery() != null
                    || origin.getFragment() != null || !origin.getPath().isEmpty()) return false;
            if (publicUrl != null && origin.getScheme().equals(publicUrl.getScheme())
                    && origin.getAuthority().equals(publicUrl.getAuthority())) return true;
            return Set.of("http", "https").contains(origin.getScheme()) && LOOPBACK.contains(origin.getHost());
        } catch (IllegalArgumentException exception) { return false; }
    }
}
