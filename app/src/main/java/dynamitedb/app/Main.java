package dynamitedb.app;

import dynamitedb.app.cli.CliClient;
import dynamitedb.app.driver.DriverCli;
import dynamitedb.app.mock.MockRuntime;
import dynamitedb.app.server.TcpServer;
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
      output.println("server --mock [--host 127.0.0.1] [--port 7777]");
      output.println("cli [--host 127.0.0.1] [--port 7777]");
      output.println("driver [--suite | --file path] (standalone mock driver)");
      return;
    }
    Map<String, String> options = options(args);
    switch (args[0]) {
      case "server" -> {
        if (!options.containsKey("--mock"))
          throw new IllegalArgumentException(
              "Use --mock while QO/SM/CCM/FRM and the execution engine are still stubs. Real wiring is injected through DbmsBootstrap.");
        MockRuntime runtime = new MockRuntime();
        DbmsConfig config =
            new DbmsConfig(
                options.getOrDefault("--host", "127.0.0.1"),
                Integer.parseInt(options.getOrDefault("--port", "7777")));
        try (TcpServer server = new DbmsBootstrap(runtime.processor(), () -> {}).start(config)) {
          Thread shutdown = new Thread(server::stop, "qp-shutdown");
          Runtime.getRuntime().addShutdownHook(shutdown);
          output.println("Mock QP server listening on " + config.host() + ":" + server.port());
          try {
            new CountDownLatch(1).await();
          } finally {
            try {
              Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
              // The JVM is already executing this shutdown hook.
            }
          }
        }
      }
      case "cli" ->
          new CliClient()
              .run(
                  options.getOrDefault("--host", "127.0.0.1"),
                  Integer.parseInt(options.getOrDefault("--port", "7777")));
      case "driver" -> {
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
      if (key.equals("--mock") || key.equals("--suite")) options.put(key, "true");
      else if (java.util.Set.of("--host", "--port", "--file").contains(key)
          && index + 1 < args.length) options.put(key, args[++index]);
      else throw new IllegalArgumentException("Unknown or incomplete option: " + key);
    }
    return options;
  }
}
