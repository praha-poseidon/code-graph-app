package com.poseidon.codegraph.app.task;

import java.time.Instant;

public record AnalysisTaskEvent(
        String id,
        String taskId,
        String stage,
        String status,
        String message,
        String details,
        Instant startedAt,
        Instant finishedAt) {
}
