package com.poseidon.codegraph.app.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
@DependsOnDatabaseInitialization
public class RepositoryConfigStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SecretCodec secretCodec;
    private final RowMapper<RepositoryConfig> rowMapper = (rs, rowNum) -> new RepositoryConfig(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("git_repo_url"),
        rs.getString("git_branch"),
        readList(rs.getString("languages")),
        rs.getString("auth_type"),
        rs.getString("access_token"),
        rs.getString("ssh_private_key"),
        rs.getString("ssh_passphrase"),
        readList(rs.getString("endpoint_rule_sources")),
        rs.getString("status"),
        instant(rs.getTimestamp("last_analyzed_at")),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at"))
    );

    public RepositoryConfigStore(JdbcTemplate jdbc, ObjectMapper objectMapper, SecretCodec secretCodec) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.secretCodec = secretCodec;
    }

    public List<RepositoryConfig> findAll() {
        return jdbc.query("SELECT * FROM repository_config ORDER BY updated_at DESC, id DESC", rowMapper);
    }

    public Optional<RepositoryConfig> findById(long id) {
        return jdbc.query("SELECT * FROM repository_config WHERE id = ?", rowMapper, id).stream().findFirst();
    }

    @Transactional("repositoryTransactionManager")
    public RepositoryConfig create(RepositoryRequest request) {
        validate(request);
        String url = request.gitRepoUrl().trim();
        String urlHash = RepositoryIdentity.hash(RepositoryIdentity.canonical(url));
        String auth = authType(request);
        jdbc.update("""
            INSERT INTO repository_config
                (name, git_repo_url, git_repo_url_hash, git_branch, languages, auth_type, access_token,
                 ssh_private_key, ssh_passphrase, endpoint_rule_sources, status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IDLE', CURRENT_TIMESTAMP)
            """,
            inferName(url), url, urlHash, branch(request), writeList(languages(request)), auth,
            "ACCESS_TOKEN".equals(auth) ? secretCodec.encrypt(request.accessToken()) : null,
            "SSH".equals(auth) ? secretCodec.encrypt(request.sshPrivateKey()) : null,
            "SSH".equals(auth) ? secretCodec.encrypt(request.sshPassphrase()) : null,
            writeList(safeList(request.endpointRuleSources())));
        RepositoryConfig created = jdbc.query("SELECT * FROM repository_config WHERE git_repo_url = ?", rowMapper, url)
            .stream().findFirst().orElseThrow(() -> new IllegalStateException("仓库保存后读取失败"));
        insertIdentity(created);
        return created;
    }

    @Transactional("repositoryTransactionManager")
    public RepositoryConfig update(long id, RepositoryRequest request) {
        validate(request);
        RepositoryConfig existing = findById(id)
            .orElseThrow(() -> new IllegalArgumentException("仓库不存在: " + id));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task WHERE repository_id = ? AND status IN ('QUEUED', 'RUNNING')", Long.class, id) > 0) {
            throw new IllegalArgumentException("请等待任务结束后再修改仓库配置");
        }
        String auth = authType(request);
        String accessToken = "ACCESS_TOKEN".equals(auth)
            ? encryptedOrExisting(request.accessToken(), existing.accessToken()) : null;
        String sshKey = "SSH".equals(auth)
            ? encryptedOrExisting(request.sshPrivateKey(), existing.sshPrivateKey()) : null;
        String passphrase = "SSH".equals(auth)
            ? encryptedOrExisting(request.sshPassphrase(), existing.sshPassphrase()) : null;
        String url = request.gitRepoUrl().trim();
        String urlHash = RepositoryIdentity.hash(RepositoryIdentity.canonical(url));
        String canonical = RepositoryIdentity.canonical(url);
        jdbc.update("UPDATE repository_identity SET repository_key = ?, canonical_repository = ? WHERE repository_id = ?",
            RepositoryIdentity.hash(canonical), canonical, id);
        jdbc.update("""
            UPDATE repository_config
               SET name = ?, git_repo_url = ?, git_repo_url_hash = ?, git_branch = ?, languages = ?, auth_type = ?,
                   access_token = ?, ssh_private_key = ?, ssh_passphrase = ?, endpoint_rule_sources = ?,
                   status = 'IDLE', updated_at = CURRENT_TIMESTAMP
             WHERE id = ?
            """,
            inferName(url), url, urlHash, branch(request), writeList(languages(request)), auth,
            accessToken, sshKey, passphrase, writeList(safeList(request.endpointRuleSources())), id);
        return findById(id).orElseThrow();
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM repository_config WHERE id = ?", id);
    }

    public void updateStatus(long id, String status, Instant lastAnalyzedAt) {
        jdbc.update("""
            UPDATE repository_config
               SET status = ?, last_analyzed_at = ?, updated_at = CURRENT_TIMESTAMP
             WHERE id = ?
            """, status, lastAnalyzedAt == null ? null : Timestamp.from(lastAnalyzedAt), id);
    }

    public RepositoryConfig decrypted(RepositoryConfig config) {
        return new RepositoryConfig(
            config.id(), config.name(), config.gitRepoUrl(), config.gitBranch(), config.languages(),
            config.authType(), secretCodec.decrypt(config.accessToken()), secretCodec.decrypt(config.sshPrivateKey()),
            secretCodec.decrypt(config.sshPassphrase()), config.endpointRuleSources(), config.status(),
            config.lastAnalyzedAt(), config.createdAt(), config.updatedAt());
    }

    private String encryptedOrExisting(String plain, String existing) {
        return plain == null || plain.isBlank() ? existing : secretCodec.encrypt(plain);
    }

    private void validate(RepositoryRequest request) {
        if (request == null || request.gitRepoUrl() == null || request.gitRepoUrl().isBlank()) {
            throw new IllegalArgumentException("仓库地址不能为空");
        }
        if (languages(request).size() != 1) {
            throw new IllegalArgumentException("每个仓库只能选择一种源码语言");
        }
        RepositoryIdentity.canonical(request.gitRepoUrl());
        String auth = authType(request);
        if (!List.of("NONE", "SSH", "ACCESS_TOKEN").contains(auth)) {
            throw new IllegalArgumentException("不支持的认证方式: " + auth);
        }
    }

    private List<String> languages(RepositoryRequest request) {
        return safeList(request.languages()).stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    }

    private String authType(RepositoryRequest request) {
        return request.authType() == null || request.authType().isBlank()
            ? "NONE"
            : request.authType().trim().toUpperCase(Locale.ROOT);
    }

    private String branch(RepositoryRequest request) {
        return request.gitBranch() == null ? "" : request.gitBranch().trim();
    }

    private String inferName(String gitRepoUrl) {
        String canonical = RepositoryIdentity.canonical(gitRepoUrl);
        return canonical.substring(canonical.indexOf('/') + 1);
    }

    private void insertIdentity(RepositoryConfig config) {
        String canonical = RepositoryIdentity.canonical(config.gitRepoUrl());
        jdbc.update("INSERT INTO repository_identity (repository_id, project_id, repository_key, canonical_repository) VALUES (?, ?, ?, ?)",
            config.id(), RepositoryIdentity.projectId(canonical), RepositoryIdentity.hash(canonical), canonical);
    }

    public RepositoryIdentity identity(long repositoryId) {
        return jdbc.queryForObject("SELECT * FROM repository_identity WHERE repository_id = ?", (rs, row) -> new RepositoryIdentity(
            rs.getString("project_id"), rs.getString("repository_key"), rs.getString("canonical_repository")), repositoryId);
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception exception) {
            throw new IllegalStateException("仓库配置序列化失败", exception);
        }
    }

    private List<String> readList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception exception) {
            throw new IllegalStateException("仓库配置读取失败", exception);
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
