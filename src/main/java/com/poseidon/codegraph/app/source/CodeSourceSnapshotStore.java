package com.poseidon.codegraph.app.source;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Repository
@DependsOnDatabaseInitialization
public class CodeSourceSnapshotStore {
    private static final String GZIP = "gzip";
    private final JdbcTemplate jdbc;

    public CodeSourceSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Start a new staging snapshot. Retries reuse the task id and replace its old staging rows. */
    @Transactional("repositoryTransactionManager")
    public void begin(String taskId, long repositoryId, String commitSha) {
        jdbc.update("DELETE FROM code_source_snapshot WHERE task_id = ?", taskId);
        jdbc.update("""
            INSERT INTO code_source_snapshot (repository_id, task_id, commit_sha, status, created_at)
            VALUES (?, ?, ?, 'STAGING', CURRENT_TIMESTAMP)
            """, repositoryId, taskId, commitSha);
    }

    public void saveFile(String taskId, long repositoryId, String commitSha,
            String path, String language, Path file) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || java.util.Arrays.asList(path.split("/")).contains("..")) {
            throw new IllegalArgumentException("source path must be repository-relative");
        }
        try {
            byte[] raw = Files.readAllBytes(file);
            if (containsBinary(raw)) {
                return;
            }
            String contentHash = sha256(raw);
            byte[] compressed = gzip(raw);
            try {
                jdbc.update("""
                    INSERT INTO code_source_blob (content_sha256, content_encoding, content_bytes, size_bytes)
                    VALUES (?, ?, ?, ?)
                    """, contentHash, GZIP, compressed, (long) raw.length);
            } catch (DuplicateKeyException ignored) {
                // The same source bytes may already be stored by another snapshot.
            }
            Long snapshotId = jdbc.queryForObject(
                "SELECT id FROM code_source_snapshot WHERE task_id = ? AND repository_id = ?",
                Long.class, taskId, repositoryId);
            if (snapshotId == null) {
                throw new IllegalStateException("源码快照不存在: taskId=" + taskId);
            }
            String pathHash = sha256(path.getBytes(StandardCharsets.UTF_8));
            jdbc.update("DELETE FROM code_source_file WHERE snapshot_id = ? AND path_hash = ?",
                snapshotId, pathHash);
            jdbc.update("""
                INSERT INTO code_source_file (snapshot_id, path_hash, path, language, content_sha256, size_bytes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, snapshotId, pathHash, path, language, contentHash, (long) raw.length);
        } catch (IOException exception) {
            throw new IllegalStateException("保存源码快照失败: " + path, exception);
        }
    }

    /** Promote only after the graph task has parsed every source file successfully. */
    @Transactional("repositoryTransactionManager")
    public void promote(String taskId, long repositoryId) {
        jdbc.update("""
            UPDATE code_source_snapshot SET status = 'SUPERSEDED'
             WHERE repository_id = ? AND status = 'CURRENT'
            """, repositoryId);
        int updated = jdbc.update("""
            UPDATE code_source_snapshot
               SET status = 'CURRENT', promoted_at = CURRENT_TIMESTAMP
             WHERE repository_id = ? AND task_id = ? AND status = 'STAGING'
            """, repositoryId, taskId);
        if (updated != 1) {
            throw new IllegalStateException("源码快照未找到或已被处理: taskId=" + taskId);
        }
    }

    @Transactional("repositoryTransactionManager")
    public void discard(String taskId) {
        jdbc.update("DELETE FROM code_source_snapshot WHERE task_id = ? AND status = 'STAGING'", taskId);
    }

    public Optional<CodeSourceSnapshot> findCurrent(long repositoryId, String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        String pathHash = sha256(path.getBytes(StandardCharsets.UTF_8));
        return jdbc.query("""
            SELECT s.repository_id, s.task_id, s.commit_sha, f.path, f.language, f.size_bytes,
                   b.content_encoding, b.content_bytes
              FROM code_source_snapshot s
              JOIN code_source_file f ON f.snapshot_id = s.id AND f.path_hash = ?
              JOIN code_source_blob b ON b.content_sha256 = f.content_sha256
             WHERE s.repository_id = ? AND s.status = 'CURRENT'
            """, (rs, row) -> new CodeSourceSnapshot(
                rs.getLong("repository_id"), rs.getString("task_id"), rs.getString("commit_sha"),
                rs.getString("path"), rs.getString("language"), rs.getLong("size_bytes"),
                decode(rs.getString("content_encoding"), rs.getBytes("content_bytes"))),
            pathHash, repositoryId).stream().findFirst();
    }

    private String decode(String encoding, byte[] bytes) {
        try {
            byte[] raw = GZIP.equalsIgnoreCase(encoding) ? gunzip(bytes) : bytes;
            return new String(raw, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("读取源码快照失败", exception);
        }
    }

    private byte[] gzip(byte[] value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(128, value.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value);
        }
        return output.toByteArray();
    }

    private byte[] gunzip(byte[] value) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(value));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            gzip.transferTo(output);
            return output.toByteArray();
        }
    }

    private boolean containsBinary(byte[] value) {
        int nul = 0;
        for (byte current : value) {
            if (current == 0) nul++;
        }
        return nul > 0;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("计算源码摘要失败", exception);
        }
    }
}
