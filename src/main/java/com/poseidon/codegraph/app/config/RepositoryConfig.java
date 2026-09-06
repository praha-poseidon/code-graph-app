package com.poseidon.codegraph.app.config;

import java.time.Instant;
import java.util.List;

public record RepositoryConfig(
        long id,
        String name,
        String gitRepoUrl,
        String gitBranch,
        List<String> languages,
        String authType,
        String accessToken,
        String sshPrivateKey,
        String sshPassphrase,
        List<String> endpointRuleSources,
        String status,
        Instant lastAnalyzedAt,
        Instant createdAt,
        Instant updatedAt) {
}
