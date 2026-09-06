package com.poseidon.codegraph.app.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "code-graph.storage.type=memory",
    "code-graph.tasks.enabled=false",
    "code-graph.source-snapshots.retained-superseded=0",
    "spring.datasource.url=jdbc:h2:mem:source-snapshot;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always"
})
class CodeSourceSnapshotStoreTest {
    @Autowired private CodeSourceSnapshotStore store;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM code_source_snapshot");
        jdbc.update("DELETE FROM code_source_blob");
        jdbc.update("DELETE FROM repository_config");
        jdbc.update("""
            INSERT INTO repository_config
                (id, name, git_repo_url, git_repo_url_hash, git_branch, languages, auth_type, status)
            VALUES (1, 'source-test', 'https://example.test/source.git', NULL, 'main', 'java', 'NONE', 'IDLE')
            """);
    }

    @Test
    void promotesCompleteCompressedSourceAndReplacesCurrentVersion(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("Service.java");
        Files.writeString(file, "package demo;\nclass Service {\n  void save() {}\n}\n", StandardCharsets.UTF_8);

        store.begin("task-one", 1L, "abc123");
        store.saveFile("task-one", 1L, "abc123", "src/Service.java", "java", file);
        assertThat(store.findCurrent(1L, "src/Service.java")).isEmpty();

        store.promote("task-one", 1L);
        CodeSourceSnapshot first = store.findCurrent(1L, "src/Service.java").orElseThrow();
        assertThat(first.commitSha()).isEqualTo("abc123");
        assertThat(first.language()).isEqualTo("java");
        assertThat(first.content()).contains("void save() {}");

        Files.writeString(file, "package demo;\nclass Service {\n  void load() {}\n}\n", StandardCharsets.UTF_8);
        store.begin("task-two", 1L, "def456");
        store.saveFile("task-two", 1L, "def456", "src/Service.java", "java", file);
        store.promote("task-two", 1L);

        CodeSourceSnapshot second = store.findCurrent(1L, "src/Service.java").orElseThrow();
        assertThat(second.taskId()).isEqualTo("task-two");
        assertThat(second.commitSha()).isEqualTo("def456");
        assertThat(second.content()).contains("void load() {}").doesNotContain("void save() {}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM code_source_snapshot WHERE status = 'SUPERSEDED'", Long.class))
            .isZero();
    }

    @Test
    void failedStagingSnapshotIsNotReadable() throws Exception {
        Path file = tempFile("README.md", "hello\n");
        store.begin("task-failed", 1L, "failed");
        store.saveFile("task-failed", 1L, "failed", "README.md", "text", file);
        store.discard("task-failed");
        assertThat(store.findCurrent(1L, "README.md")).isEmpty();
    }

    private Path tempFile(String name, String content) throws Exception {
        Path file = Files.createTempFile("source-snapshot-", "-" + name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
