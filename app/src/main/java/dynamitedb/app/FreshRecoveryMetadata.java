package dynamitedb.app;

import dynamitedb.contracts.recovery.SnapshotMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;

final class FreshRecoveryMetadata {
  private FreshRecoveryMetadata() {}

  static Predicate<Path> predicate(Path manifest, Path wal, SnapshotMetadata snapshot) {
    return entry -> validate(entry, manifest, wal, snapshot);
  }

  private static boolean validate(Path entry, Path manifest, Path wal, SnapshotMetadata snapshot) {
    if (entry.equals(manifest)) return true;
    if (entry.equals(wal)) {
      try {
        return Files.size(wal) == 36;
      } catch (IOException failure) {
        return false;
      }
    }
    if (entry.getFileName().toString().equals("recovery-snapshots.properties")) {
      Properties properties = new Properties();
      try (var input = Files.newInputStream(entry, StandardOpenOption.READ)) {
        properties.load(input);
        String prefix = "snapshot." + snapshot.id() + ".";
        return properties.size() == 4
            && snapshot.id().toString().equals(properties.getProperty(prefix + "id"))
            && snapshot.walLineage().toString().equals(properties.getProperty(prefix + "lineage"))
            && "0".equals(properties.getProperty(prefix + "baseLsn"))
            && Instant.EPOCH.toString().equals(properties.getProperty(prefix + "createdAt"));
      } catch (IOException failure) {
        return false;
      }
    }
    if (!entry.getFileName().toString().equals("snapshots")) return false;
    Path snapshotDirectory = entry.resolve(snapshot.id().toString());
    if (Files.isSymbolicLink(entry) || Files.isSymbolicLink(snapshotDirectory)) return false;
    Path metadata = snapshotDirectory.resolve("generation.meta");
    if (Files.isSymbolicLink(metadata)) return false;
    try (var children = Files.list(entry)) {
      if (!children.toList().equals(List.of(snapshotDirectory))) return false;
    } catch (IOException failure) {
      return false;
    }
    try (var children = Files.list(snapshotDirectory)) {
      if (!children
          .map(path -> path.getFileName().toString())
          .toList()
          .equals(List.of("generation.meta"))) return false;
    } catch (IOException failure) {
      return false;
    }
    Properties properties = new Properties();
    try (var input = Files.newInputStream(metadata)) {
      properties.load(input);
      return snapshot.id().toString().equals(properties.getProperty("id"))
          && snapshot.walLineage().toString().equals(properties.getProperty("walLineage"))
          && "0".equals(properties.getProperty("baseLsn"))
          && Instant.EPOCH.toString().equals(properties.getProperty("createdAt"));
    } catch (IOException failure) {
      return false;
    }
  }
}
