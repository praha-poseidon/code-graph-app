CREATE TABLE IF NOT EXISTS repository_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    git_repo_url VARCHAR(2048) NOT NULL,
    -- Keep the full display/clone URL, but index a fixed-width digest so MySQL
    -- utf8mb4 never exceeds its 3072-byte index-key limit.
    git_repo_url_hash CHAR(64) NULL,
    git_branch VARCHAR(255) NOT NULL,
    languages VARCHAR(512) NOT NULL,
    auth_type VARCHAR(32) NOT NULL,
    access_token TEXT,
    ssh_private_key TEXT,
    ssh_passphrase TEXT,
    endpoint_rule_sources TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    last_analyzed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_repository_config_url UNIQUE (git_repo_url_hash)
);

CREATE TABLE IF NOT EXISTS repository_identity (
    repository_id BIGINT PRIMARY KEY,
    project_id VARCHAR(26) NOT NULL UNIQUE,
    repository_key VARCHAR(64) NOT NULL UNIQUE,
    canonical_repository VARCHAR(2048) NOT NULL,
    FOREIGN KEY (repository_id) REFERENCES repository_config(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS analysis_task (
    id VARCHAR(36) PRIMARY KEY,
    repository_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress_current INT NOT NULL DEFAULT 0,
    progress_total INT NOT NULL DEFAULT 0,
    message VARCHAR(1024),
    error_details TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    lease_owner VARCHAR(255),
    lease_until TIMESTAMP NULL,
    heartbeat_at TIMESTAMP NULL,
    next_attempt_at TIMESTAMP NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_analysis_task_status_created (status, created_at),
    INDEX idx_analysis_task_repository_created (repository_id, created_at),
    CONSTRAINT fk_analysis_task_repository
        FOREIGN KEY (repository_id) REFERENCES repository_config(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS analysis_worker (
    worker_id VARCHAR(255) PRIMARY KEY,
    host_name VARCHAR(255) NOT NULL,
    process_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_task_id VARCHAR(36),
    started_at TIMESTAMP NOT NULL,
    heartbeat_at TIMESTAMP NOT NULL,
    stopped_at TIMESTAMP NULL,
    last_error TEXT
);

CREATE TABLE IF NOT EXISTS analysis_task_event (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message VARCHAR(1024),
    details TEXT,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP NULL,
    INDEX idx_analysis_task_event_task_started (task_id, started_at),
    CONSTRAINT fk_analysis_task_event_task
        FOREIGN KEY (task_id) REFERENCES analysis_task(id) ON DELETE CASCADE
);
