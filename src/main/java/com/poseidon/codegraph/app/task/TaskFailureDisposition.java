package com.poseidon.codegraph.app.task;

public enum TaskFailureDisposition {
    RETRY_SCHEDULED,
    FAILED,
    CANCELED,
    LEASE_LOST
}
