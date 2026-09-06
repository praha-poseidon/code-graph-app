package com.poseidon.codegraph.app.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "code-graph.storage.type=memory",
    "code-graph.tasks.enabled=false",
    "code-graph.tasks.max-attempts=3",
    "code-graph.security.master-key=task-store-test-key",
    "spring.datasource.url=jdbc:h2:mem:task-store;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always"
})
class AnalysisTaskStoreTest {

    @Autowired
    private AnalysisTaskStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalysisWorkerStore workerStore;

    @Autowired
    private AnalysisTaskEventStore eventStore;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM analysis_task_event");
        jdbc.update("DELETE FROM analysis_worker");
        jdbc.update("DELETE FROM analysis_task");
        jdbc.update("DELETE FROM repository_config");
        insertRepository(1L, "one");
        insertRepository(2L, "two");
    }

    @Test
    void workersCannotClaimTheSameTaskOrRunTwoTasksForOneRepository() {
        AnalysisTask first = store.enqueue(1L);
        AnalysisTask second = store.enqueue(1L);
        AnalysisTask otherRepository = store.enqueue(2L);

        AnalysisTask claimed = store.claimNext("worker-a", Duration.ofSeconds(30)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(first.id());
        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(claimed.leaseOwner()).isEqualTo("worker-a");

        AnalysisTask claimedByOtherWorker = store.claimNext("worker-b", Duration.ofSeconds(30)).orElseThrow();
        assertThat(claimedByOtherWorker.id()).isEqualTo(otherRepository.id());
        assertThat(store.findById(second.id()).orElseThrow().status()).isEqualTo("QUEUED");
    }

    @Test
    void simultaneousWorkersHaveExactlyOneClaimWinner() throws Exception {
        store.enqueue(1L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> claimAfterSignal("worker-a", ready, start));
            var second = executor.submit(() -> claimAfterSignal("worker-b", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long winners = java.util.stream.Stream.of(first.get(), second.get())
                .filter(Optional::isPresent)
                .count();
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void healthyLeaseIsNotRecoveredAndHeartbeatExtendsIt() {
        AnalysisTask task = store.enqueue(1L);
        AnalysisTask claimed = store.claimNext("worker-a", Duration.ofSeconds(30)).orElseThrow();

        assertThat(store.recoverExpiredLeases()).isZero();
        assertThat(store.renewLease(task.id(), "worker-b", Duration.ofMinutes(1))).isFalse();
        assertThat(store.renewLease(task.id(), "worker-a", Duration.ofMinutes(1))).isTrue();
        assertThat(store.findById(task.id()).orElseThrow().leaseUntil())
            .isAfter(claimed.leaseUntil());
    }

    @Test
    void expiredLeaseRetriesThenCancellationStopsTheClaimedTask() {
        AnalysisTask task = store.enqueue(1L);
        store.claimNext("worker-a", Duration.ofSeconds(30)).orElseThrow();
        expire(task.id());

        assertThat(store.recoverExpiredLeases()).isEqualTo(1);
        assertThat(store.findById(task.id()).orElseThrow().status()).isEqualTo("QUEUED");

        store.claimNext("worker-b", Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.cancel(task.id()).orElseThrow().cancelRequested()).isTrue();
        assertThat(store.failOrRetry(task.id(), "worker-b", "stopped", "canceled", Duration.ZERO))
            .isEqualTo(TaskFailureDisposition.CANCELED);
        assertThat(store.findById(task.id()).orElseThrow().status()).isEqualTo("CANCELED");
    }

    @Test
    void expiredLeaseFailsAfterMaximumAttempts() {
        AnalysisTask task = store.enqueue(1L);
        jdbc.update("UPDATE analysis_task SET max_attempts = 1 WHERE id = ?", task.id());
        store.claimNext("worker-a", Duration.ofSeconds(30)).orElseThrow();
        expire(task.id());

        assertThat(store.recoverExpiredLeases()).isEqualTo(1);
        assertThat(store.findById(task.id()).orElseThrow().status()).isEqualTo("FAILED");
    }

    @Test
    void workerRegistrationAndHeartbeatExposeCurrentTask() {
        AnalysisWorkerIdentity identity = new AnalysisWorkerIdentity("worker-monitor-test");
        workerStore.register(identity);
        workerStore.heartbeat(identity.workerId(), "task-visible-in-dashboard");

        AnalysisWorker worker = workerStore.findAll().getFirst();
        assertThat(worker.workerId()).isEqualTo("worker-monitor-test");
        assertThat(worker.status()).isEqualTo("WORKING");
        assertThat(worker.activeTaskId()).isEqualTo("task-visible-in-dashboard");

        workerStore.markOffline(identity.workerId());
        assertThat(workerStore.findAll().getFirst().status()).isEqualTo("OFFLINE");
    }

    @Test
    void taskEventsPreserveStageHistoryAndDurationBoundaries() {
        AnalysisTask task = store.enqueue(1L);
        String cloneEvent = eventStore.start(task.id(), "CLONE", "正在克隆仓库");
        eventStore.succeed(cloneEvent, "仓库克隆完成");
        eventStore.skip(task.id(), "BUILD", "当前语言无需预构建");
        String parseEvent = eventStore.start(task.id(), "PARSE", "开始解析");
        eventStore.update(parseEvent, "1/2 · main.go");

        var events = eventStore.findByTaskId(task.id());
        assertThat(events).hasSize(4);
        assertThat(events.get(0).stage()).isEqualTo("QUEUED");
        assertThat(events.get(1).stage()).isEqualTo("CLONE");
        assertThat(events.get(1).status()).isEqualTo("SUCCEEDED");
        assertThat(events.get(1).finishedAt()).isNotNull();
        assertThat(events.get(2).status()).isEqualTo("SKIPPED");
        assertThat(events.get(3).status()).isEqualTo("RUNNING");
        assertThat(events.get(3).message()).isEqualTo("1/2 · main.go");
        assertThat(events.get(3).finishedAt()).isNull();
    }

    @Test
    void queuedCancellationIsVisibleInTaskHistory() {
        AnalysisTask task = store.enqueue(1L);

        assertThat(store.cancel(task.id()).orElseThrow().status()).isEqualTo("CANCELED");

        var events = eventStore.findByTaskId(task.id());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).stage()).isEqualTo("QUEUED");
        assertThat(events.get(1).stage()).isEqualTo("COMPLETE");
        assertThat(events.get(1).status()).isEqualTo("CANCELED");
    }

    private void expire(String taskId) {
        jdbc.update("UPDATE analysis_task SET lease_until = ? WHERE id = ?",
            Timestamp.from(Instant.now().minusSeconds(1)), taskId);
    }

    private Optional<AnalysisTask> claimAfterSignal(
            String workerId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return store.claimNext(workerId, Duration.ofSeconds(30));
    }

    private void insertRepository(long id, String name) {
        jdbc.update("""
            INSERT INTO repository_config
                (id, name, git_repo_url, git_repo_url_hash, git_branch, languages, auth_type, status)
            VALUES (?, ?, ?, ?, 'main', 'go', 'NONE', 'IDLE')
            """, id, name, "https://example.test/" + name + ".git", null);
    }
}
