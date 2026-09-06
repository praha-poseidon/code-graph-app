package com.poseidon.codegraph.app.task;

import com.poseidon.codegraph.app.config.RepositoryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public final class GitWorkspace {

    private final Path workspaceRoot;

    public GitWorkspace(@Value("${code-graph.workspace-root}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
    }

    public Path cloneRepository(String taskId, RepositoryConfig repository) {
        Path taskRoot = workspaceRoot.resolve(taskId).normalize();
        if (!taskRoot.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("非法任务工作目录");
        }
        Path checkout = taskRoot.resolve("repository");
        try {
            Files.createDirectories(taskRoot);
            List<String> command = new ArrayList<>(List.of("git", "clone", "--depth", "1"));
            if (repository.gitBranch() != null && !repository.gitBranch().isBlank()) {
                command.addAll(List.of("--branch", repository.gitBranch(), "--single-branch"));
            }
            command.add(repository.gitRepoUrl());
            command.add(checkout.toString());
            Map<String, String> environment = authenticationEnvironment(taskRoot, repository);
            run(command, taskRoot, environment, Duration.ofMinutes(5));
            return checkout;
        } catch (IOException exception) {
            throw new IllegalStateException("创建 Git 工作目录失败", exception);
        }
    }

    public void cleanup(String taskId) {
        Path taskRoot = workspaceRoot.resolve(taskId).normalize();
        if (!taskRoot.startsWith(workspaceRoot) || taskRoot.equals(workspaceRoot) || !Files.exists(taskRoot)) {
            return;
        }
        try (var paths = Files.walk(taskRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A failed cleanup is retried when the outer task directory is removed by operations.
                }
            });
        } catch (IOException ignored) {
            // Analysis result must not be hidden by cleanup failure.
        }
    }

    public String run(List<String> command, Path directory, Map<String, String> environment, Duration timeout) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
            builder.environment().putAll(environment);
            process = builder.start();
            Process startedProcess = process;
            CompletableFuture<String> outputFuture = new CompletableFuture<>();
            Thread.ofVirtual().name("codegraph-command-output").start(() -> {
                try {
                    outputFuture.complete(new String(startedProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException exception) {
                    outputFuture.completeExceptionally(exception);
                }
            });
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminateTree(process);
                throw new IllegalStateException("命令执行超时: " + command.getFirst());
            }
            String output = outputFuture.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("命令执行失败: " + command.getFirst() + "\n" + tail(output));
            }
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动命令: " + command.getFirst(), exception);
        } catch (InterruptedException exception) {
            terminateTree(process);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令执行被中断: " + command.getFirst(), exception);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException exception) {
            terminateTree(process);
            throw new IllegalStateException("读取命令输出失败: " + command.getFirst(), exception);
        }
    }

    private void terminateTree(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            descendants.get(index).destroyForcibly();
        }
        process.destroyForcibly();
    }

    private Map<String, String> authenticationEnvironment(Path taskRoot, RepositoryConfig repository) throws IOException {
        Map<String, String> environment = new HashMap<>();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        if ("ACCESS_TOKEN".equals(repository.authType()) && repository.accessToken() != null) {
            Path askPass = taskRoot.resolve("git-askpass.sh");
            Files.writeString(askPass, """
                #!/bin/sh
                case "$1" in
                  *Username*) printf '%s\\n' 'oauth2' ;;
                  *) printf '%s\\n' "$CODEGRAPH_GIT_ACCESS_TOKEN" ;;
                esac
                """, StandardCharsets.UTF_8);
            executable(askPass);
            environment.put("GIT_ASKPASS", askPass.toString());
            environment.put("CODEGRAPH_GIT_ACCESS_TOKEN", repository.accessToken());
        }
        if ("SSH".equals(repository.authType()) && repository.sshPrivateKey() != null) {
            Path privateKey = taskRoot.resolve("id_repository");
            Files.writeString(privateKey, repository.sshPrivateKey(), StandardCharsets.UTF_8);
            privateFile(privateKey);
            environment.put("GIT_SSH_COMMAND", "ssh -i " + privateKey
                + " -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new");
            if (repository.sshPassphrase() != null && !repository.sshPassphrase().isBlank()) {
                Path askPass = taskRoot.resolve("ssh-askpass.sh");
                Files.writeString(askPass, "#!/bin/sh\nprintf '%s\\n' \"$CODEGRAPH_SSH_PASSPHRASE\"\n", StandardCharsets.UTF_8);
                executable(askPass);
                environment.put("SSH_ASKPASS", askPass.toString());
                environment.put("SSH_ASKPASS_REQUIRE", "force");
                environment.put("DISPLAY", "codegraph:0");
                environment.put("CODEGRAPH_SSH_PASSPHRASE", repository.sshPassphrase());
            }
        }
        return environment;
    }

    private void executable(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
            path.toFile().setExecutable(true, true);
        }
    }

    private void privateFile(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }

    private String tail(String output) {
        if (output == null || output.length() <= 4000) {
            return output == null ? "" : output;
        }
        return output.substring(output.length() - 4000);
    }
}
