package com.poseidon.codegraph.app;

import com.poseidon.codegraph.app.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {"code-graph.tasks.enabled=false", "spring.datasource.url=jdbc:h2:mem:identity-store;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class RepositoryIdentityStoreTest {
    @Autowired RepositoryConfigStore store;
    @Autowired JdbcTemplate jdbc;

    @Test void canonicalDuplicateRollsBackAndDifferentGroupsHaveDifferentStableIds() {
        var a = store.create(request("https://github.com/group-a/demo.git", "main"));
        var b = store.create(request("https://github.com/group-b/demo.git", "main"));
        String id = store.identity(a.id()).projectId();
        assertThat(id).isNotEqualTo(store.identity(b.id()).projectId());
        assertThat(a.name()).isEqualTo("group-a/demo");
        assertThatThrownBy(() -> store.create(request("git@github.com:group-a/demo.git", "main")))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(store.findAll()).hasSize(2);
        store.update(a.id(), request("git@github.com:group-a/demo.git", "release"));
        assertThat(store.identity(a.id()).projectId()).isEqualTo(id);
        assertThatThrownBy(() -> store.update(a.id(), request("https://github.com/group-b/demo.git", "main")))
            .isInstanceOf(DuplicateKeyException.class);
        assertThat(store.identity(a.id()).canonicalRepository()).isEqualTo("github.com/group-a/demo");
        assertThat(store.findById(a.id()).orElseThrow().gitBranch()).isEqualTo("release");
    }

    @Test void missingIdentityIsRejectedRatherThanFallingBackToDisplayName() {
        var old = store.create(request("https://github.com/legacy/old.git", "main"));
        jdbc.update("DELETE FROM repository_identity WHERE repository_id = ?", old.id());
        jdbc.update("UPDATE repository_config SET name = 'old' WHERE id = ?", old.id());
        assertThatThrownBy(() -> store.identity(old.id()))
            .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(store.findById(old.id()).orElseThrow().name()).isEqualTo("old");
        store.delete(old.id());
    }

    private RepositoryRequest request(String url, String branch) {
        return new RepositoryRequest(url, branch, List.of("java"), "NONE", null, null, null, List.of(), false);
    }
}
