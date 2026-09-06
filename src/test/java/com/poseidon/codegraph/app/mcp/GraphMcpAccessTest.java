package com.poseidon.codegraph.app.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class GraphMcpAccessTest {
    @Test void noTokenOnlyAllowsLoopbackAndKnownHost() throws Exception {
        var access = new GraphMcpAccess("", "", true);
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.setServerName("localhost"); request.setRemoteAddr("10.0.0.9");
        var denied = new MockHttpServletResponse();
        access.doFilter(request, denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(403);
        request.setRemoteAddr("127.0.0.1"); request.setServerName("attacker.example");
        denied = new MockHttpServletResponse();
        access.doFilter(request, denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(403);
        request.setServerName("localhost");
        var allowed = new MockHttpServletResponse();
        access.doFilter(request, allowed, new MockFilterChain());
        assertThat(allowed.getStatus()).isEqualTo(200);
    }
    @Test void remotePublicAddressRequiresTokenAndRejectsEmbeddedSecrets() {
        assertThatThrownBy(() -> new GraphMcpAccess("", "https://graph.example/mcp", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphMcpAccess("secret", "https://graph.example/mcp?token=secret", true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new GraphMcpAccess("secret", "https://graph.example/mcp", true).endpoint()).isEqualTo("https://graph.example/mcp");
    }
    @Test void disabledEndpointReturnsNotFound() throws Exception {
        var response = new MockHttpServletResponse();
        new GraphMcpAccess("", "", false).doFilter(new MockHttpServletRequest("POST", "/mcp"), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(404);
    }
}
