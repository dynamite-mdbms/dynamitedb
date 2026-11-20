package dynamitedb.integration.acceptance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PackagedServerProcess implements AutoCloseable {
  static final String LAUNCHER_PROPERTY = "dynamitedb.acceptance.launcher";
  private static final Pattern READY_LINE =
      Pattern.compile("\\blistening on 127\\.0\\.0\\.1:(\\d+)\\b");
  private static final int OUTPUT_TAIL_LINES = 100;
  private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

  private final Process process;
  private final CompletableFuture<Integer> readyPort = new CompletableFuture<>();
  private final ArrayDeque<String> outputTail = new ArrayDeque<>();

  private PackagedServerProcess(Process process) {
    this.process = process;
    Thread.ofVirtual().name("packaged-server-output").start(this::drainOutput);
  }

  static PackagedServerProcess start(Path dataDirectory, String protocol) throws IOException {
    return start(dataDirectory, protocol, 60);
  }

  static PackagedServerProcess start(Path dataDirectory, String protocol, long checkpointSeconds)
      throws IOException {
    PackagedServerProcess server = launch(dataDirectory, protocol, checkpointSeconds);
    try {
      server.awaitReady(DEFAULT_STARTUP_TIMEOUT);
      return server;
    } catch (IOException | InterruptedException failure) {
      try {
        server.close();
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IOException("Packaged server did not become ready: " + server.output(), failure);
    }
  }

  static PackagedServerProcess launch(Path dataDirectory, String protocol, long checkpointSeconds)
      throws IOException {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    Objects.requireNonNull(protocol, "protocol");
    if (checkpointSeconds <= 0) {
      throw new IllegalArgumentException("Checkpoint interval must be positive");
    }
    String launcherValue = System.getProperty(LAUNCHER_PROPERTY);
    if (launcherValue == null || launcherValue.isBlank()) {
      throw new IllegalStateException("Missing packaged app launcher property");
    }
    Path launcher = Path.of(launcherValue).toAbsolutePath().normalize();
    if (!Files.isRegularFile(launcher)) {
      throw new IOException("Packaged app launcher does not exist: " + launcher);
    }

    List<String> command = new ArrayList<>();
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
      command.add("cmd.exe");
      command.add("/c");
    }
    command.add(launcher.toString());
    command.addAll(
        List.of(
            "server",
            "--real",
            "--host",
            "127.0.0.1",
            "--port",
            "0",
            "--data-dir",
            dataDirectory.toAbsolutePath().normalize().toString(),
            "--protocol",
            protocol,
            "--checkpoint-interval-seconds",
            Long.toString(checkpointSeconds)));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    return new PackagedServerProcess(process);
  }

  int awaitReady(Duration timeout) throws IOException, InterruptedException {
    try {
      return readyPort.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (ExecutionException failure) {
      throw new IOException(
          "Packaged server exited before readiness: " + output(), failure.getCause());
    } catch (TimeoutException failure) {
      throw new IOException(
          "Timed out waiting for packaged server readiness: " + output(), failure);
    }
  }

  int port() throws IOException, InterruptedException {
    return awaitReady(DEFAULT_STARTUP_TIMEOUT);
  }

  boolean isAlive() {
    return process.isAlive();
  }

  long pid() {
    return process.pid();
  }

  boolean awaitExit(Duration timeout) throws InterruptedException {
    return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  void killForcibly() throws IOException {
    if (!process.isAlive()) return;
    process.destroyForcibly();
    try {
      if (!process.waitFor(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new IOException("Packaged server did not exit after forced termination: " + output());
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while terminating packaged server", interrupted);
    }
  }

  String output() {
    synchronized (outputTail) {
      return String.join(System.lineSeparator(), outputTail);
    }
  }

  static int parseReadyPort(String line) {
    Matcher matcher = READY_LINE.matcher(Objects.requireNonNull(line, "line"));
    if (!matcher.find()) return -1;
    try {
      int port = Integer.parseInt(matcher.group(1));
      return port > 0 && port <= 65535 ? port : -1;
    } catch (NumberFormatException invalidPort) {
      return -1;
    }
  }

  private void drainOutput() {
    try (var reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        appendOutput(line);
        int port = parseReadyPort(line);
        if (port > 0) readyPort.complete(port);
      }
      readyPort.completeExceptionally(
          new IOException(
              "Server process closed output before readiness (exit " + exitCode() + ")"));
    } catch (IOException failure) {
      readyPort.completeExceptionally(failure);
    }
  }

  private void appendOutput(String line) {
    synchronized (outputTail) {
      outputTail.addLast(line);
      while (outputTail.size() > OUTPUT_TAIL_LINES) outputTail.removeFirst();
    }
  }

  private String exitCode() {
    try {
      return Integer.toString(process.exitValue());
    } catch (IllegalThreadStateException running) {
      return "running";
    }
  }

  @Override
  public void close() throws IOException {
    if (!process.isAlive()) return;
    process.destroy();
    try {
      if (!process.waitFor(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
        process.destroyForcibly();
        if (!process.waitFor(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
          throw new IOException("Packaged server did not stop: " + output());
        }
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while stopping packaged server", interrupted);
    }
  }
}
