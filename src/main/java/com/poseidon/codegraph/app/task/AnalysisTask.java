package com.poseidon.codegraph.app.task;

import java.time.Instant;

public record AnalysisTask(
        String id,
        long repositoryId,
        String status,
        int progressCurrent,
        int progressTotal,
        String message,
        String errorDetails,
        int attemptCount,
        int maxAttempts,
        String leaseOwner,
        Instant leaseUntil,
        Instant heartbeatAt,
        Instant nextAttemptAt,
        boolean cancelRequested,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {
}
