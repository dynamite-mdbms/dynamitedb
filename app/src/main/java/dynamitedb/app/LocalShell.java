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
    for (int index = 0; index < args.length; index++) {
      switch (args[index]) {
        case "--help" -> help = true;
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
    return new Options(dataDirectory, help);
  }

  static Path prepareDataDirectory(Path dataDirectory) throws IOException {
    Path data = dataDirectory.toAbsolutePath().normalize();
    if (Files.exists(data)) {
      if (!Files.isDirectory(data)) {
        throw new IllegalArgumentException("Data path is not a directory: " + data);
      }
      if (Files.notExists(data.resolve("active-generation.properties"))) {
        try (Stream<Path> entries = Files.list(data)) {
          if (entries.findAny().isPresent()) {
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
      output.println("shell [--data-dir PATH] [--help]");
      output.println("  Opens a persistent local database in PATH (default: ./data).");
      return;
    }

    Path dataDirectory = prepareDataDirectory(options.dataDirectory());
    DbmsConfig config = new DbmsConfig(0, dataDirectory, ProtocolType.STRICT_2PL);
    PersistentRuntime runtime = new PersistentRuntime(config);
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

  record Options(Path dataDirectory, boolean help) {}
}
