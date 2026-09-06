package com.poseidon.codegraph.app.adapter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poseidon.codegraph.engine.application.model.CodeFunctionDO;
import com.poseidon.codegraph.storage.memory.repository.InMemoryCodeGraphRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GraphViewPropertiesTest {
    @Test void graphReadsAllStoredBusinessPropertiesIncludingFalseAndNull() {
        var repository = new InMemoryCodeGraphRepository();
        var function = new CodeFunctionDO();
        function.setId("project:uuid::fn:demo.Service.save()");
        function.setName("save");
        function.setLanguage("java");
        function.setStartLine(12);
        function.setEndLine(18);
        function.setSignature("save()");
        function.setIsStatic(false);
        function.setReturnType("void");
        function.setModifiers(List.of("public"));
        repository.insertFunctionsBatch(List.of(function));
        var controller = new GraphViewController(repository, new ObjectMapper());
        var properties = controller.overview(null, null, null, 10, 10).getData().nodes().getFirst().properties();
        assertThat(properties).containsEntry("startLine", 12).containsEntry("endLine", 18)
            .containsEntry("signature", "save()").containsEntry("isStatic", false)
            .containsEntry("gitBranch", null).containsEntry("modifiers", List.of("public"))
            .doesNotContainKeys("accessToken", "sshPrivateKey");
    }
}
