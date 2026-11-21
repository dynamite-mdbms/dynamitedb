package dynamitedb.app;

import dynamitedb.app.cli.CliClient;
import dynamitedb.contracts.transaction.ProtocolType;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

final class LocalShell {
  private LocalShell() {}

  static Options parse(String[] args) {
    Path dataDirectory = DbmsConfig.defaults().dataDirectory();
    boolean help = false;
    boolean geneticOptimizer = false;
    for (int index = 0; index < args.length; index++) {
      switch (args[index]) {
        case "--help" -> help = true;
        case "--genetic-optimizer" -> geneticOptimizer = true;
        case "--data-dir" -> {
          if (index + 1 == args.length || args[index + 1].startsWith("--")) {
            throw new IllegalArgumentException("--data-dir requires a path");
          }
          String path = args[++index];
          if (path.isBlank()) throw new IllegalArgumentException("--data-dir requires a path");
          dataDirectory = Path.of(path);
        }
        default -> throw new IllegalArgumentException("Unknown shell option: " + args[index]);
      }
    }
    return new Options(dataDirectory, help, geneticOptimizer);
  }

  static Path prepareDataDirectory(Path dataDirectory) throws IOException {
    Path data = dataDirectory.toAbsolutePath().normalize();
    if (Files.exists(data)) {
      if (!Files.isDirectory(data)) {
        throw new IllegalArgumentException("Data path is not a directory: " + data);
      }
      if (Files.notExists(data.resolve("active-generation.properties"))) {
        try (Stream<Path> entries = Files.list(data)) {
          if (entries.anyMatch(entry -> !RuntimeRootLock.isLockFile(entry))) {
            throw new IllegalArgumentException(
                "Refusing nonempty directory without a database manifest: " + data);
          }
        }
      }
    } else {
      Files.createDirectories(data);
    }
    return data;
  }

  static void run(String[] args, PrintWriter output) throws Exception {
    Options options = parse(args);
    if (options.help()) {
      output.println("shell [--data-dir PATH] [--genetic-optimizer] [--help]");
      output.println("  --data-dir PATH selects the database folder (default: ./data).");
      output.println(
          "  --genetic-optimizer lets the genetic algorithm (QO-18) propose join orders"
              + " (default: off).");
      output.println(
          "  :read PATH runs a compatible UTF-8 SQL script; paths are relative to the current directory.");
      output.println("  Example: :read scripts/setup.sql");
      output.println(
          "  :read stops at the first error; earlier statements may already have committed.");
      output.println(
          "  Interactive keys: Up/Down browse this session's command history; paste SQL, then press Enter.");
      return;
    }

    Path dataDirectory = prepareDataDirectory(options.dataDirectory());
    DbmsConfig config = new DbmsConfig(0, dataDirectory, ProtocolType.STRICT_2PL);
    PersistentRuntime runtime = new PersistentRuntime(config, options.geneticOptimizer());
    AtomicBoolean closed = new AtomicBoolean();
    Runnable closeOnce =
        () -> {
          if (closed.compareAndSet(false, true)) runtime.close();
        };
    Thread shutdown = new Thread(closeOnce, "local-shell-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdown);
    try {
      DbmsBootstrap bootstrap = runtime.bootstrap();
      bootstrap.start(config, "127.0.0.1");
      runtime.startPeriodicCheckpoints(Duration.ofSeconds(60));
      new CliClient().runLocal("127.0.0.1", bootstrap.port(), dataDirectory);
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdown);
      } catch (IllegalStateException ignored) {
        // The JVM is already executing this shutdown hook.
      }
      closeOnce.run();
    }
  }

  record Options(Path dataDirectory, boolean help, boolean geneticOptimizer) {}
}
