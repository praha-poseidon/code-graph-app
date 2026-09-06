package com.poseidon.codegraph.app.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

@Component
public final class AnalysisWorkerIdentity {

    private final String workerId;
    private final String hostName;
    private final long processId;

    public AnalysisWorkerIdentity(@Value("${code-graph.tasks.worker-id:}") String configuredWorkerId) {
        hostName = resolveHostName();
        processId = ProcessHandle.current().pid();
        workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
            ? hostName + ":" + processId + ":" + UUID.randomUUID().toString().substring(0, 8)
            : configuredWorkerId.trim();
    }

    public String workerId() {
        return workerId;
    }

    public String hostName() {
        return hostName;
    }

    public long processId() {
        return processId;
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "unknown-host";
        }
    }
}
