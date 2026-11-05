package dynamitedb.app;

import dynamitedb.app.cli.CliClient;
import dynamitedb.app.driver.DriverCli;
import dynamitedb.app.mock.MockRuntime;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

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
      output.println("server --stub [--host 127.0.0.1] [--port 7777] [--data-dir data]");
      output.println(
          "  stub runtime: module wiring is real; query, storage, recovery and concurrency"
              + " operations remain stubs/defaults");
      output.println("server --mock [--host 127.0.0.1] [--port 7777] (legacy demo behavior)");
      output.println("cli [--host 127.0.0.1] [--port 7777]");
      output.println("driver [--suite | --file path] (standalone mock driver)");
      return;
    }
    Map<String, String> options = options(args);
    switch (args[0]) {
      case "server" -> {
        requireOnly(options, "--mock", "--stub", "--host", "--port", "--data-dir");
        boolean mock = options.containsKey("--mock");
        boolean stub = options.containsKey("--stub");
        if (mock == stub)
          throw new IllegalArgumentException("Choose exactly one of --stub or --mock");
        String host = options.getOrDefault("--host", "127.0.0.1");
        if (host.isBlank()) throw new IllegalArgumentException("Host must not be blank");
        int port = Integer.parseInt(options.getOrDefault("--port", "7777"));
        DbmsConfig defaults = DbmsConfig.defaults();
        DbmsConfig config =
            new DbmsConfig(
                port,
                Path.of(options.getOrDefault("--data-dir", defaults.dataDirectory().toString())),
                defaults.protocol());
        DbmsBootstrap bootstrap;
        String mode;
        if (stub) {
          StubRuntime runtime = new StubRuntime(config);
          bootstrap = runtime.bootstrap();
          mode = "Stub QP server (operations remain stubs/defaults)";
        } else {
          if (options.containsKey("--data-dir")) {
            throw new IllegalArgumentException("--data-dir is only valid with server --stub");
          }
          bootstrap = new DbmsBootstrap(new MockRuntime().processor(), () -> {});
          mode = "Mock QP server";
        }
        try {
          bootstrap.start(config, host);
          Thread shutdown = new Thread(bootstrap::shutdown, "qp-shutdown");
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
          bootstrap.shutdown();
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
      if (key.equals("--mock") || key.equals("--stub") || key.equals("--suite"))
        options.put(key, "true");
      else if (java.util.Set.of("--host", "--port", "--file", "--data-dir").contains(key)
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
}
