package com.poseidon.codegraph.app.task;

import java.time.Instant;

public record AnalysisWorker(
        String workerId,
        String hostName,
        long processId,
        String status,
        String activeTaskId,
        Instant startedAt,
        Instant heartbeatAt,
        Instant stoppedAt,
        String lastError) {
}
