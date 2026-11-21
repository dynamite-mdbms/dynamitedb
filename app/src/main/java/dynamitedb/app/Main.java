package dynamitedb.app;

import dynamitedb.app.cli.CliClient;
import dynamitedb.app.driver.DriverCli;
import dynamitedb.app.mock.MockRuntime;
import dynamitedb.contracts.transaction.ProtocolType;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    PrintWriter output = new PrintWriter(System.out, true);
    try {
      run(args, output);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception failure) {
      output.println("ERROR: " + failure.getMessage());
      System.exit(1);
    }
  }

  private static void run(String[] args, PrintWriter output) throws Exception {
    if (args.length == 0 || args[0].equals("--help")) {
      output.println("shell [--data-dir PATH] [--genetic-optimizer] [--help]");
      output.println("server --stub [--host 127.0.0.1] [--port 7777] [--data-dir data]");
      output.println(
          "  stub runtime: module wiring is real; query, storage, recovery and concurrency"
              + " operations remain stubs/defaults");
      output.println(
          "server --real [--host 127.0.0.1] [--port 7777] [--data-dir data]"
              + " [--protocol strict-2pl|strict-timestamp] [--checkpoint-interval-seconds 60]"
              + " [--genetic-optimizer]");
      output.println(
          "  real runtime: persistent storage, WAL recovery, sample database and SQL server");
      output.println("server --mock [--host 127.0.0.1] [--port 7777] (legacy demo behavior)");
      output.println("cli [--host 127.0.0.1] [--port 7777]");
      output.println("driver [--suite | --file path] (standalone mock driver)");
      return;
    }
    if (args[0].equals("shell")) {
      LocalShell.run(java.util.Arrays.copyOfRange(args, 1, args.length), output);
      return;
    }
    Map<String, String> options = options(args);
    switch (args[0]) {
      case "server" -> {
        requireOnly(
            options,
            "--mock",
            "--stub",
            "--real",
            "--host",
            "--port",
            "--data-dir",
            "--protocol",
            "--checkpoint-interval-seconds",
            "--genetic-optimizer");
        boolean mock = options.containsKey("--mock");
        boolean stub = options.containsKey("--stub");
        boolean real = options.containsKey("--real");
        if ((mock ? 1 : 0) + (stub ? 1 : 0) + (real ? 1 : 0) != 1)
          throw new IllegalArgumentException("Choose exactly one of --real, --stub or --mock");
        if (!real && options.containsKey("--checkpoint-interval-seconds"))
          throw new IllegalArgumentException(
              "--checkpoint-interval-seconds requires server --real");
        boolean genetic = options.containsKey("--genetic-optimizer");
        if (!real && genetic)
          throw new IllegalArgumentException("--genetic-optimizer requires server --real");
        if (mock && options.containsKey("--protocol"))
          throw new IllegalArgumentException("--protocol is not valid with server --mock");
        String host = options.getOrDefault("--host", "127.0.0.1");
        if (host.isBlank()) throw new IllegalArgumentException("Host must not be blank");
        int port = Integer.parseInt(options.getOrDefault("--port", "7777"));
        Duration checkpointInterval =
            checkpointInterval(options.getOrDefault("--checkpoint-interval-seconds", "60"));
        ProtocolType protocol = protocol(options.getOrDefault("--protocol", "strict-2pl"));
        DbmsConfig defaults = DbmsConfig.defaults();
        DbmsConfig config =
            new DbmsConfig(
                port,
                Path.of(options.getOrDefault("--data-dir", defaults.dataDirectory().toString())),
                protocol);
        DbmsBootstrap bootstrap;
        PersistentRuntime runtime = null;
        String mode;
        if (real) {
          runtime = new PersistentRuntime(config, genetic);
          bootstrap = runtime.bootstrap();
          mode =
              "Persistent DynamiteDB runtime (checkpoint every "
                  + checkpointInterval.toSeconds()
                  + " seconds"
                  + (genetic ? ", genetic join optimizer on" : "")
                  + ")";
        } else if (stub) {
          StubRuntime stubRuntime = new StubRuntime(config);
          bootstrap = stubRuntime.bootstrap();
          mode = "Stub QP server (operations remain stubs/defaults)";
        } else {
          if (options.containsKey("--data-dir")) {
            throw new IllegalArgumentException("--data-dir is only valid with server --stub");
          }
          bootstrap = new DbmsBootstrap(new MockRuntime().processor(), () -> {});
          mode = "Mock QP server";
        }
        PersistentRuntime activeRuntime = runtime;
        Runnable shutdownAction =
            activeRuntime == null ? bootstrap::shutdown : activeRuntime::close;
        AtomicBoolean closed = new AtomicBoolean();
        Runnable closeOnce =
            () -> {
              if (closed.compareAndSet(false, true)) shutdownAction.run();
            };
        try {
          bootstrap.start(config, host);
          if (runtime != null) runtime.startPeriodicCheckpoints(checkpointInterval);
          Thread shutdown = new Thread(closeOnce, "qp-shutdown");
          Runtime.getRuntime().addShutdownHook(shutdown);
          output.println(mode + " listening on " + host + ":" + bootstrap.port());
          try {
            new CountDownLatch(1).await();
          } finally {
            try {
              Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
              // The JVM is already executing this shutdown hook.
            }
          }
        } finally {
          closeOnce.run();
        }
      }
      case "cli" -> {
        requireOnly(options, "--host", "--port");
        new CliClient()
            .run(
                options.getOrDefault("--host", "127.0.0.1"),
                Integer.parseInt(options.getOrDefault("--port", "7777")));
      }
      case "driver" -> {
        requireOnly(options, "--suite", "--file");
        DriverCli driver = new DriverCli(new MockRuntime().processor());
        if (options.containsKey("--suite") || options.containsKey("--file")) {
          var report =
              options.containsKey("--file")
                  ? driver.runFile(Path.of(options.get("--file")))
                  : driver.runDemoSuite();
          DriverCli.printReport(report, output);
          if (!report.passed()) throw new IllegalStateException("Driver suite failed");
        } else driver.run(new InputStreamReader(System.in, StandardCharsets.UTF_8), output);
      }
      default -> throw new IllegalArgumentException("Unknown mode. Use --help.");
    }
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> options = new HashMap<>();
    for (int index = 1; index < args.length; index++) {
      String key = args[index];
      if (key.equals("--mock")
          || key.equals("--stub")
          || key.equals("--real")
          || key.equals("--suite")
          || key.equals("--genetic-optimizer")) options.put(key, "true");
      else if (java.util.Set.of(
                  "--host",
                  "--port",
                  "--file",
                  "--data-dir",
                  "--protocol",
                  "--checkpoint-interval-seconds")
              .contains(key)
          && index + 1 < args.length) options.put(key, args[++index]);
      else throw new IllegalArgumentException("Unknown or incomplete option: " + key);
    }
    return options;
  }

  private static void requireOnly(Map<String, String> options, String... allowed) {
    if (!java.util.Set.of(allowed).containsAll(options.keySet())) {
      throw new IllegalArgumentException("Option is not valid for this mode");
    }
  }

  static ProtocolType protocol(String value) {
    return switch (value) {
      case "strict-2pl" -> ProtocolType.STRICT_2PL;
      case "strict-timestamp" -> ProtocolType.STRICT_TIMESTAMP;
      default -> throw new IllegalArgumentException("Unknown concurrency protocol: " + value);
    };
  }

  static Duration checkpointInterval(String value) {
    long seconds = Long.parseLong(value);
    if (seconds <= 0 || seconds > Long.MAX_VALUE / 1_000_000_000L)
      throw new IllegalArgumentException(
          "Checkpoint interval must be a positive number of seconds");
    return Duration.ofSeconds(seconds);
  }
}
