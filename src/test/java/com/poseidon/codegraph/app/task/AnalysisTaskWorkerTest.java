package com.poseidon.codegraph.app.task;

import com.poseidon.codegraph.app.config.RepositoryConfig;
import com.poseidon.codegraph.app.config.RepositoryConfigStore;
import com.poseidon.codegraph.starter.service.IncrementalUpdateService;
import com.poseidon.codegraph.starter.service.IncrementalUpdateSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AnalysisTaskWorkerTest {

    private static final String ENDPOINT_RULE = "rule http { match GET build path /users }";

    @TempDir
    Path tempDir;

    @Test
    void mapsSupportedSourceExtensionsToParserLanguages() {
        assertThat(AnalysisTaskWorker.languageFor(Path.of("Api.java"))).isEqualTo("java");
        assertThat(AnalysisTaskWorker.languageFor(Path.of("main.go"))).isEqualTo("go");
        assertThat(AnalysisTaskWorker.languageFor(Path.of("page.tsx"))).isEqualTo("typescript");
        assertThat(AnalysisTaskWorker.languageFor(Path.of("worker.mjs"))).isEqualTo("javascript");
        assertThat(AnalysisTaskWorker.languageFor(Path.of("service.py"))).isEqualTo("python");
        assertThat(AnalysisTaskWorker.languageFor(Path.of("index.php"))).isEqualTo("php");
        assertThat(AnalysisTaskWorker.languageFor(Path.of("App.kt"))).isEqualTo("kotlin");
        assertThat(AnalysisTaskWorker.languageFor(Path.of("View.swift"))).isEqualTo("swift");
        assertThat(AnalysisTaskWorker.languageFor(Path.of("README.md"))).isEqualTo("unknown");
    }

    @Test
    void parserFailureStillClosesTaskSessionAndCleansWorkspace() throws Exception {
        AnalysisTaskStore taskStore = mock(AnalysisTaskStore.class);
        AnalysisTaskEventStore taskEventStore = mock(AnalysisTaskEventStore.class);
        RepositoryConfigStore repositoryStore = mock(RepositoryConfigStore.class);
        GitWorkspace workspace = mock(GitWorkspace.class);
        IncrementalUpdateService updateService = mock(IncrementalUpdateService.class);
        IncrementalUpdateSession session = mock(IncrementalUpdateSession.class);
        AnalysisTask task = task("task-1");
        AnalysisWorkerStore workerStore = mock(AnalysisWorkerStore.class);
        AnalysisWorkerIdentity identity = new AnalysisWorkerIdentity("worker-test");
        RepositoryConfig repository = repository();
        Path checkout = Files.createDirectories(tempDir.resolve("checkout"));
        Files.writeString(checkout.resolve("main.go"), "package main\n");

        when(repositoryStore.findById(7L)).thenReturn(Optional.of(repository));
        when(repositoryStore.decrypted(repository)).thenReturn(repository);
        when(repositoryStore.identity(7L)).thenReturn(new com.poseidon.codegraph.app.config.RepositoryIdentity("project-test", "key", "example/demo"));
        when(workspace.cloneRepository("task-1", repository)).thenReturn(checkout);
        when(updateService.openSession("go")).thenReturn(session);
        when(taskStore.isOwnedAndRunning("task-1", "worker-test")).thenReturn(true);
        when(taskStore.findById("task-1")).thenReturn(Optional.of(task));
        when(taskStore.updateProgress(anyString(), eq("worker-test"), anyInt(), anyInt(), anyString()))
            .thenReturn(true);
        doThrow(new IllegalStateException("semantic parser failed"))
            .when(session).handleFileAdded(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                any(String[].class), any(String[].class), anyList(), anyList());

        new AnalysisTaskWorker(taskStore, taskEventStore, repositoryStore, workspace, updateService,
            workerStore, identity, false, 30000, 0).execute(task);

        verify(session).close();
        verify(workspace).cleanup("task-1");
        verify(taskStore).failOrRetry(eq("task-1"), eq("worker-test"),
            eq("semantic parser failed"), anyString(), any());
    }

    @Test
    void sessionCloseFailureCannotPreventWorkspaceCleanup() throws Exception {
        AnalysisTaskStore taskStore = mock(AnalysisTaskStore.class);
        AnalysisTaskEventStore taskEventStore = mock(AnalysisTaskEventStore.class);
        RepositoryConfigStore repositoryStore = mock(RepositoryConfigStore.class);
        GitWorkspace workspace = mock(GitWorkspace.class);
        IncrementalUpdateService updateService = mock(IncrementalUpdateService.class);
        IncrementalUpdateSession session = mock(IncrementalUpdateSession.class);
        AnalysisTask task = task("task-2");
        AnalysisWorkerStore workerStore = mock(AnalysisWorkerStore.class);
        AnalysisWorkerIdentity identity = new AnalysisWorkerIdentity("worker-test");
        RepositoryConfig repository = repository();
        Path checkout = Files.createDirectories(tempDir.resolve("checkout-close"));
        Files.writeString(checkout.resolve("main.go"), "package main\n");

        when(repositoryStore.findById(7L)).thenReturn(Optional.of(repository));
        when(repositoryStore.decrypted(repository)).thenReturn(repository);
        when(repositoryStore.identity(7L)).thenReturn(new com.poseidon.codegraph.app.config.RepositoryIdentity("project-test", "key", "example/demo"));
        when(workspace.cloneRepository("task-2", repository)).thenReturn(checkout);
        when(updateService.openSession("go")).thenReturn(session);
        when(taskStore.isOwnedAndRunning("task-2", "worker-test")).thenReturn(true);
        when(taskStore.findById("task-2")).thenReturn(Optional.of(task));
        when(taskStore.updateProgress(anyString(), eq("worker-test"), anyInt(), anyInt(), anyString()))
            .thenReturn(true);
        when(taskStore.succeed("task-2", "worker-test", 1)).thenReturn(true);
        when(session.language()).thenReturn("go");
        doThrow(new IllegalStateException("close failed")).when(session).close();

        new AnalysisTaskWorker(taskStore, taskEventStore, repositoryStore, workspace, updateService,
            workerStore, identity, false, 30000, 0).execute(task);

        verify(session).close();
        verify(workspace).cleanup("task-2");
        verify(taskStore).succeed("task-2", "worker-test", 1);
    }

    @Test
    void filesOfOneLanguageReuseOneTaskSessionAndAreProcessedSequentially() throws Exception {
        AnalysisTaskStore taskStore = mock(AnalysisTaskStore.class);
        AnalysisTaskEventStore taskEventStore = mock(AnalysisTaskEventStore.class);
        RepositoryConfigStore repositoryStore = mock(RepositoryConfigStore.class);
        GitWorkspace workspace = mock(GitWorkspace.class);
        IncrementalUpdateService updateService = mock(IncrementalUpdateService.class);
        IncrementalUpdateSession session = mock(IncrementalUpdateSession.class);
        AnalysisWorkerStore workerStore = mock(AnalysisWorkerStore.class);
        AnalysisWorkerIdentity identity = new AnalysisWorkerIdentity("worker-test");
        AnalysisTask task = task("task-3");
        RepositoryConfig repository = repository();
        Path checkout = Files.createDirectories(tempDir.resolve("checkout-reuse"));
        Files.writeString(checkout.resolve("a.go"), "package demo\n");
        Files.writeString(checkout.resolve("b.go"), "package demo\n");

        when(repositoryStore.findById(7L)).thenReturn(Optional.of(repository));
        when(repositoryStore.decrypted(repository)).thenReturn(repository);
        when(repositoryStore.identity(7L)).thenReturn(new com.poseidon.codegraph.app.config.RepositoryIdentity("project-test", "key", "example/demo"));
        when(workspace.cloneRepository("task-3", repository)).thenReturn(checkout);
        when(updateService.openSession("go")).thenReturn(session);
        when(taskStore.isOwnedAndRunning("task-3", "worker-test")).thenReturn(true);
        when(taskStore.findById("task-3")).thenReturn(Optional.of(task));
        when(taskStore.updateProgress(anyString(), eq("worker-test"), anyInt(), anyInt(), anyString()))
            .thenReturn(true);
        when(taskStore.succeed("task-3", "worker-test", 2)).thenReturn(true);

        new AnalysisTaskWorker(taskStore, taskEventStore, repositoryStore, workspace, updateService,
            workerStore, identity, false, 30000, 0).execute(task);

        verify(updateService, times(1)).openSession("go");
        verify(session, times(2)).handleFileAdded(
            anyString(), anyString(), anyString(), anyString(), anyString(),
            any(String[].class), any(String[].class), eq(List.of(ENDPOINT_RULE)), eq(List.of()));
        verify(session).close();
        verify(taskStore).succeed("task-3", "worker-test", 2);
    }

    private AnalysisTask task(String id) {
        Instant now = Instant.now();
        return new AnalysisTask(
            id, 7L, "RUNNING", 0, 0, null, null,
            1, 3, "worker-test", now.plusSeconds(30), now, null, false,
            now, now, null, now);
    }

    private RepositoryConfig repository() {
        return new RepositoryConfig(
            7L,
            "demo",
            "git@example/demo.git",
            "main",
            List.of("go"),
            "NONE",
            null,
            null,
            null,
            List.of(ENDPOINT_RULE),
            "IDLE",
            null,
            Instant.now(),
            Instant.now());
    }
}
