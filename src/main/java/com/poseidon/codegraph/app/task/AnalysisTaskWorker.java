package com.poseidon.codegraph.app.task;

import com.poseidon.codegraph.app.config.RepositoryConfig;
import com.poseidon.codegraph.app.config.RepositoryConfigStore;
import com.poseidon.codegraph.starter.service.IncrementalUpdateService;
import com.poseidon.codegraph.starter.service.IncrementalUpdateSession;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@ConditionalOnProperty(name = "code-graph.tasks.enabled", havingValue = "true", matchIfMissing = true)
public final class AnalysisTaskWorker {

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
        ".git", ".idea", ".vscode", "node_modules", "vendor", "target", "build", "dist", ".build");

    private final AnalysisTaskStore taskStore;
    private final AnalysisTaskEventStore taskEventStore;
    private final RepositoryConfigStore repositoryStore;
    private final GitWorkspace workspace;
    private final IncrementalUpdateService incrementalUpdateService;
    private final AnalysisWorkerStore workerStore;
    private final AnalysisWorkerIdentity identity;
    private final boolean autoBuild;
    private final Duration leaseDuration;
    private final Duration retryDelay;
    private final AtomicReference<String> activeTaskId = new AtomicReference<>();
    private final AtomicReference<String> lostLeaseTaskId = new AtomicReference<>();
    private volatile boolean ready;

    public AnalysisTaskWorker(
            AnalysisTaskStore taskStore,
            AnalysisTaskEventStore taskEventStore,
            RepositoryConfigStore repositoryStore,
            GitWorkspace workspace,
            IncrementalUpdateService incrementalUpdateService,
            AnalysisWorkerStore workerStore,
            AnalysisWorkerIdentity identity,
            @Value("${code-graph.tasks.auto-build:true}") boolean autoBuild,
            @Value("${code-graph.tasks.lease-ms:30000}") long leaseMillis,
            @Value("${code-graph.tasks.retry-delay-ms:5000}") long retryDelayMillis) {
        this.taskStore = taskStore;
        this.taskEventStore = taskEventStore;
        this.repositoryStore = repositoryStore;
        this.workspace = workspace;
        this.incrementalUpdateService = incrementalUpdateService;
        this.workerStore = workerStore;
        this.identity = identity;
        this.autoBuild = autoBuild;
        this.leaseDuration = Duration.ofMillis(Math.max(5000, leaseMillis));
        this.retryDelay = Duration.ofMillis(Math.max(0, retryDelayMillis));
    }

    @Scheduled(fixedDelayString = "${code-graph.tasks.poll-delay-ms:1000}")
    public void poll() {
        if (!ready) {
            return;
        }
        taskStore.recoverExpiredLeases();
        taskStore.claimNext(identity.workerId(), leaseDuration).ifPresent(task -> {
            activeTaskId.set(task.id());
            workerStore.heartbeat(identity.workerId(), task.id());
            try {
                execute(task);
            } finally {
                activeTaskId.compareAndSet(task.id(), null);
                lostLeaseTaskId.compareAndSet(task.id(), null);
                workerStore.heartbeat(identity.workerId(), null);
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        workerStore.register(identity);
        int recovered = taskStore.recoverExpiredLeases();
        if (recovered > 0) {
            log.info("已恢复 {} 个租约过期的分析任务", recovered);
        }
        ready = true;
    }

    @Scheduled(fixedDelayString = "${code-graph.tasks.heartbeat-ms:5000}")
    public void heartbeat() {
        if (!ready) return;
        String taskId = activeTaskId.get();
        workerStore.heartbeat(identity.workerId(), taskId);
        workerStore.markStaleOffline(leaseDuration.multipliedBy(2));
        if (taskId != null && !taskStore.renewLease(taskId, identity.workerId(), leaseDuration)) {
            lostLeaseTaskId.set(taskId);
        }
    }

    @PreDestroy
    public void shutdown() {
        ready = false;
        workerStore.markOffline(identity.workerId());
    }

    void execute(AnalysisTask task) {
        RepositoryConfig encrypted = repositoryStore.findById(task.repositoryId()).orElse(null);
        if (encrypted == null) {
            String details = "repositoryId=" + task.repositoryId();
            TaskFailureDisposition disposition = taskStore.failOrRetry(
                task.id(), identity.workerId(), "仓库不存在", details, retryDelay);
            taskEventStore.record(task.id(), "COMPLETE",
                disposition == TaskFailureDisposition.RETRY_SCHEDULED ? "RETRYING" : "FAILED",
                disposition == TaskFailureDisposition.RETRY_SCHEDULED ? "仓库不存在，等待重试" : "仓库不存在",
                details);
            return;
        }
        repositoryStore.updateStatus(encrypted.id(), "ANALYZING", encrypted.lastAnalyzedAt());
        Map<String, IncrementalUpdateSession> sessions = new HashMap<>();
        RepositoryConfig repository = null;
        int total = 0;
        boolean parsed = false;
        boolean canceled = false;
        boolean leaseLost = false;
        Exception failure = null;
        String activeEventId = null;
        try {
            repository = repositoryStore.decrypted(encrypted);
            String graphScope = repositoryStore.identity(repository.id()).graphScope();
            checkpoint(task.id());
            progress(task.id(), 0, 0, "克隆仓库");
            activeEventId = taskEventStore.start(task.id(), "CLONE", "正在克隆仓库");
            Path checkout;
            try {
                checkout = workspace.cloneRepository(task.id(), repository);
                taskEventStore.succeed(activeEventId, "仓库克隆完成");
                activeEventId = null;
            } catch (Exception exception) {
                taskEventStore.fail(activeEventId, "仓库克隆失败", stackSummary(exception));
                activeEventId = null;
                throw exception;
            }

            List<String> classpath = prepareBuild(task.id(), checkout, repository);
            activeEventId = taskEventStore.start(task.id(), "DISCOVER", "正在扫描源码文件");
            List<Path> sourceFiles;
            try {
                sourceFiles = sourceFiles(checkout, repository.languages());
                taskEventStore.succeed(activeEventId, "发现 " + sourceFiles.size() + " 个源码文件");
                activeEventId = null;
            } catch (Exception exception) {
                taskEventStore.fail(activeEventId, "扫描源码文件失败", stackSummary(exception));
                activeEventId = null;
                throw exception;
            }
            if (sourceFiles.isEmpty()) {
                throw new IllegalStateException("仓库中没有找到所选语言的源码文件");
            }

            total = sourceFiles.size();
            progress(task.id(), 0, total, "开始解析 " + total + " 个文件");
            String[] sourceRoots = sourceRoots(checkout);
            String[] dependencies = classpath.toArray(String[]::new);
            for (String language : sourceFiles.stream().map(AnalysisTaskWorker::languageFor).distinct().toList()) {
                session(task.id(), language, sessions);
            }
            int current = 0;
            activeEventId = taskEventStore.start(task.id(), "PARSE", "开始解析 " + total + " 个文件");
            try {
                for (Path file : sourceFiles) {
                    checkpoint(task.id());
                    String projectFilePath = checkout.relativize(file).toString().replace('\\', '/');
                    String language = languageFor(file);
                    IncrementalUpdateSession session = sessions.get(language);
                    session.handleFileAdded(
                        graphScope,
                        file.toAbsolutePath().normalize().toString(),
                        projectFilePath,
                        repository.gitRepoUrl(),
                        repository.gitBranch(),
                        dependencies,
                        sourceRoots,
                        repository.endpointRuleSources(),
                        List.of());
                    current++;
                    String message = "正在解析 " + projectFilePath;
                    progress(task.id(), current, total, message);
                    taskEventStore.update(activeEventId, current + "/" + total + " · " + projectFilePath);
                }
                taskEventStore.succeed(activeEventId, "已解析 " + total + " 个文件");
                activeEventId = null;
                parsed = true;
            } catch (TaskCanceledException exception) {
                taskEventStore.cancel(activeEventId, "解析已取消");
                activeEventId = null;
                throw exception;
            } catch (Exception exception) {
                taskEventStore.fail(activeEventId, "解析失败", stackSummary(exception));
                activeEventId = null;
                throw exception;
            }
        } catch (TaskCanceledException exception) {
            canceled = true;
        } catch (TaskLeaseLostException exception) {
            leaseLost = true;
        } catch (Exception exception) {
            failure = exception;
        } finally {
            if (activeEventId != null) {
                taskEventStore.fail(activeEventId, "阶段未完成", failure == null ? null : stackSummary(failure));
            }
            closeSessions(task.id(), sessions);
            cleanupWorkspace(task.id());
        }

        if (leaseLost) {
            log.warn("停止已丢失租约的分析任务: taskId={}, workerId={}", task.id(), identity.workerId());
            taskEventStore.record(task.id(), "COMPLETE", "RETRYING", "Worker 租约已丢失，等待任务恢复", null);
            return;
        }
        if (canceled) {
            taskStore.markCanceled(task.id(), identity.workerId(), "任务已取消");
            taskEventStore.record(task.id(), "COMPLETE", "CANCELED", "任务已取消", null);
            repositoryStore.updateStatus(encrypted.id(), "IDLE", encrypted.lastAnalyzedAt());
            return;
        }
        if (failure != null) {
            log.error("异步分析任务失败: taskId={}, repositoryId={}", task.id(), task.repositoryId(), failure);
            String message = rootMessage(failure);
            TaskFailureDisposition disposition = taskStore.failOrRetry(
                task.id(), identity.workerId(), message, stackSummary(failure), retryDelay);
            workerStore.recordError(identity.workerId(), stackSummary(failure));
            taskEventStore.record(task.id(), "COMPLETE",
                disposition == TaskFailureDisposition.RETRY_SCHEDULED ? "RETRYING" : "FAILED",
                disposition == TaskFailureDisposition.RETRY_SCHEDULED ? "本次执行失败，等待重试" : "任务失败",
                stackSummary(failure));
            if (disposition == TaskFailureDisposition.FAILED) {
                repositoryStore.updateStatus(encrypted.id(), "FAILED", encrypted.lastAnalyzedAt());
            } else if (disposition == TaskFailureDisposition.CANCELED) {
                repositoryStore.updateStatus(encrypted.id(), "IDLE", encrypted.lastAnalyzedAt());
            }
            return;
        }
        if (parsed && repository != null) {
            try {
                checkpoint(task.id());
                if (!taskStore.succeed(task.id(), identity.workerId(), total)) {
                    throw new TaskLeaseLostException(task.id());
                }
                taskEventStore.record(task.id(), "COMPLETE", "SUCCEEDED", "任务完成", null);
                repositoryStore.updateStatus(repository.id(), "DONE", Instant.now());
            } catch (TaskCanceledException exception) {
                taskStore.markCanceled(task.id(), identity.workerId(), "任务已取消");
                taskEventStore.record(task.id(), "COMPLETE", "CANCELED", "任务已取消", null);
                repositoryStore.updateStatus(encrypted.id(), "IDLE", encrypted.lastAnalyzedAt());
            } catch (TaskLeaseLostException exception) {
                log.warn("完成任务前丢失租约: taskId={}, workerId={}", task.id(), identity.workerId());
            }
        }
    }

    private List<String> prepareBuild(String taskId, Path checkout, RepositoryConfig repository) {
        if (!autoBuild || repository.languages().stream().noneMatch(language ->
                "java".equals(language) || "kotlin".equals(language))) {
            taskEventStore.skip(taskId, "BUILD", "当前语言无需预构建");
            return existingClasspath(checkout);
        }
        String eventId;
        if (Files.exists(checkout.resolve("pom.xml"))) {
            progress(taskId, 0, 0, "执行 Maven 构建并解析依赖");
            eventId = taskEventStore.start(taskId, "BUILD", "正在执行 Maven 构建并解析依赖");
            String executable = Files.isRegularFile(checkout.resolve("mvnw")) ? "./mvnw" : "mvn";
            try {
                workspace.run(List.of(executable, "-q", "-DskipTests", "package",
                    "dependency:build-classpath", "-Dmdep.outputFile=.codegraph-classpath"),
                    checkout, Map.of(), Duration.ofMinutes(15));
                taskEventStore.succeed(eventId, "Maven 构建完成");
            } catch (Exception exception) {
                taskEventStore.fail(eventId, "Maven 构建失败", stackSummary(exception));
                throw exception;
            }
        } else if (Files.exists(checkout.resolve("build.gradle")) || Files.exists(checkout.resolve("build.gradle.kts"))) {
            progress(taskId, 0, 0, "执行 Gradle 构建");
            eventId = taskEventStore.start(taskId, "BUILD", "正在执行 Gradle 构建");
            String executable = Files.isRegularFile(checkout.resolve("gradlew")) ? "./gradlew" : "gradle";
            try {
                workspace.run(List.of(executable, "classes", "--no-daemon"),
                    checkout, Map.of(), Duration.ofMinutes(15));
                taskEventStore.succeed(eventId, "Gradle 构建完成");
            } catch (Exception exception) {
                taskEventStore.fail(eventId, "Gradle 构建失败", stackSummary(exception));
                throw exception;
            }
        } else {
            taskEventStore.skip(taskId, "BUILD", "未检测到 Maven 或 Gradle 构建文件");
        }
        return existingClasspath(checkout);
    }

    private IncrementalUpdateSession session(
            String taskId,
            String language,
            Map<String, IncrementalUpdateSession> sessions) {
        IncrementalUpdateSession existing = sessions.get(language);
        if (existing != null) return existing;
        String eventId = taskEventStore.start(taskId, "SESSION_START", "正在创建 " + language + " Parser Session");
        try {
            IncrementalUpdateSession created = incrementalUpdateService.openSession(language);
            sessions.put(language, created);
            taskEventStore.succeed(eventId, language + " Parser Session 已创建");
            return created;
        } catch (Exception exception) {
            taskEventStore.fail(eventId, language + " Parser Session 创建失败", stackSummary(exception));
            throw exception;
        }
    }

    private void closeSessions(String taskId, Map<String, IncrementalUpdateSession> sessions) {
        sessions.forEach((language, session) -> {
            String eventId = taskEventStore.start(taskId, "SESSION_CLOSE", "正在关闭 " + language + " Parser Session");
            try {
                session.close();
                taskEventStore.succeed(eventId, language + " Parser Session 已关闭");
            } catch (Exception exception) {
                taskEventStore.fail(eventId, language + " Parser Session 关闭失败", stackSummary(exception));
                log.warn("关闭 parser session 失败: taskId={}, language={}", taskId, language, exception);
            }
        });
    }

    private void cleanupWorkspace(String taskId) {
        String eventId = taskEventStore.start(taskId, "CLEANUP", "正在清理临时工作目录");
        try {
            workspace.cleanup(taskId);
            taskEventStore.succeed(eventId, "临时工作目录已清理");
        } catch (Exception exception) {
            taskEventStore.fail(eventId, "临时工作目录清理失败", stackSummary(exception));
            log.warn("清理任务工作目录失败: taskId={}", taskId, exception);
        }
    }

    private void progress(String taskId, int current, int total, String message) {
        if (!taskStore.updateProgress(taskId, identity.workerId(), current, total, message)) {
            throw new TaskLeaseLostException(taskId);
        }
    }

    private void checkpoint(String taskId) {
        if (taskId.equals(lostLeaseTaskId.get()) || !taskStore.isOwnedAndRunning(taskId, identity.workerId())) {
            throw new TaskLeaseLostException(taskId);
        }
        AnalysisTask current = taskStore.findById(taskId).orElseThrow(() -> new TaskLeaseLostException(taskId));
        if (current.cancelRequested()) {
            throw new TaskCanceledException(taskId);
        }
    }

    private List<String> existingClasspath(Path checkout) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        addIfDirectory(entries, checkout.resolve("target/classes"));
        addIfDirectory(entries, checkout.resolve("build/classes/java/main"));
        addIfDirectory(entries, checkout.resolve("build/classes/kotlin/main"));
        Path classpathFile = checkout.resolve(".codegraph-classpath");
        if (Files.isRegularFile(classpathFile)) {
            try {
                String raw = Files.readString(classpathFile).trim();
                if (!raw.isBlank()) {
                    for (String entry : raw.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
                        if (!entry.isBlank()) entries.add(entry.trim());
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException("读取 Maven classpath 失败", exception);
            }
        }
        return List.copyOf(entries);
    }

    private void addIfDirectory(Set<String> entries, Path path) {
        if (Files.isDirectory(path)) entries.add(path.toAbsolutePath().normalize().toString());
    }

    private List<Path> sourceFiles(Path checkout, List<String> selectedLanguages) {
        Set<String> languages = selectedLanguages.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        try (var paths = Files.walk(checkout)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> !ignored(checkout, path))
                .filter(path -> languages.contains(languageFor(path)))
                .sorted()
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("扫描源码文件失败", exception);
        }
    }

    private boolean ignored(Path checkout, Path path) {
        for (Path segment : checkout.relativize(path)) {
            if (IGNORED_DIRECTORIES.contains(segment.toString())) return true;
        }
        return false;
    }

    private String[] sourceRoots(Path checkout) {
        List<String> roots = new ArrayList<>();
        roots.add(checkout.toAbsolutePath().normalize().toString());
        for (String relative : List.of("src/main/java", "src/main/kotlin", "src", "Sources")) {
            Path candidate = checkout.resolve(relative);
            if (Files.isDirectory(candidate)) roots.add(candidate.toAbsolutePath().normalize().toString());
        }
        return roots.toArray(String[]::new);
    }

    static String languageFor(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".go")) return "go";
        if (name.endsWith(".py")) return "python";
        if (name.endsWith(".php")) return "php";
        if (name.endsWith(".kt") || name.endsWith(".kts")) return "kotlin";
        if (name.endsWith(".swift")) return "swift";
        if (name.endsWith(".js") || name.endsWith(".jsx") || name.endsWith(".mjs") || name.endsWith(".cjs")) {
            return "javascript";
        }
        if (name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".mts") || name.endsWith(".cts")) {
            return "typescript";
        }
        return "unknown";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank()
            ? current.getClass().getSimpleName()
            : current.getMessage();
    }

    private String stackSummary(Throwable throwable) {
        StringBuilder output = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (!output.isEmpty()) output.append("\nCaused by: ");
            output.append(current.getClass().getName()).append(": ").append(current.getMessage());
            current = current.getCause();
        }
        return output.length() > 12000 ? output.substring(0, 12000) : output.toString();
    }

    private static final class TaskCanceledException extends RuntimeException {
        private TaskCanceledException(String taskId) {
            super("Task canceled: " + taskId);
        }
    }

    private static final class TaskLeaseLostException extends RuntimeException {
        private TaskLeaseLostException(String taskId) {
            super("Task lease lost: " + taskId);
        }
    }
}
