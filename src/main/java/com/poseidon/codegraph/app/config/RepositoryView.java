package com.poseidon.codegraph.app.config;

import java.time.Instant;
import java.util.List;

public record RepositoryView(
        long id,
        String name,
        String gitRepoUrl,
        String gitBranch,
        List<String> languages,
        String authType,
        boolean hasAccessToken,
        boolean hasSshPrivateKey,
        int endpointRuleCount,
        String status,
        int progressCurrent,
        int progressTotal,
        String statusMessage,
        Instant lastAnalyzedAt,
        String latestTaskId,
        String projectId,
        String canonicalRepository,
        String graphScope) {
}
