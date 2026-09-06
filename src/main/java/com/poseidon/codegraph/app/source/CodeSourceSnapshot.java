package com.poseidon.codegraph.app.source;

/** A source file resolved from the repository snapshot promoted for a task. */
public record CodeSourceSnapshot(
        long repositoryId,
        String taskId,
        String commitSha,
        String path,
        String language,
        long sizeBytes,
        String content) {
}
