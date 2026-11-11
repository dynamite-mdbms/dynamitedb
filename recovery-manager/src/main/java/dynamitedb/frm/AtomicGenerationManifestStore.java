package dynamitedb.frm;

import dynamitedb.contracts.api.GenerationManifestStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.GenerationManifest;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Stores the active generation manifest using a same-directory atomic file replacement. */
public final class AtomicGenerationManifestStore implements GenerationManifestStore {
  private static final String FILE_NAME = "active-generation.properties";

  private final Path root;
  private final MockRecoveryContext context;
  private GenerationManifest active;

  public AtomicGenerationManifestStore(
      Path root, GenerationManifest initial, MockRecoveryContext context) {
    this.root = Objects.requireNonNull(root, "root");
    this.active = Objects.requireNonNull(initial, "initial");
    this.context = Objects.requireNonNull(context, "context");
  }

  /** Real manifest store used by the integrated runtime. */
  public AtomicGenerationManifestStore(Path root, GenerationManifest initial) {
    this.root = Objects.requireNonNull(root, "root");
    this.active = Objects.requireNonNull(initial, "initial");
    this.context = null;
  }

  public static boolean exists(Path root) {
    return Files.isRegularFile(Objects.requireNonNull(root, "root").resolve(FILE_NAME));
  }

  @Override
  public synchronized GenerationManifest readActive() {
    if (context != null) context.invoked("AtomicGenerationManifestStore.readActive");
    Path file = root.resolve(FILE_NAME);
    if (!Files.exists(file)) {
      return active;
    }
    Properties properties = new Properties();
    try (var input = Files.newInputStream(file)) {
      properties.load(input);
      active = fromProperties(properties);
      return active;
    } catch (IOException | IllegalArgumentException failure) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Cannot read active generation manifest", failure);
    }
  }

  @Override
  public synchronized void publishAtomically(GenerationManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    if (context != null) context.invoked("AtomicGenerationManifestStore.publishAtomically");
    Path temporary = null;
    try {
      Files.createDirectories(root);
      temporary = Files.createTempFile(root, ".active-generation-", ".tmp");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      toProperties(manifest).store(bytes, "DynamiteDB active generation");
      try (FileChannel channel =
          FileChannel.open(
              temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      try {
        Files.move(
            temporary,
            root.resolve(FILE_NAME),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        throw new DbmsException(
            ErrorCode.RECOVERY_FAILURE,
            "Filesystem does not support atomic manifest replacement",
            unsupported);
      }
      active = manifest;
    } catch (IOException failure) {
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Cannot publish generation manifest", failure);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // A leftover temporary file is never treated as the active manifest.
        }
      }
    }
  }

  private static Properties toProperties(GenerationManifest manifest) {
    Properties properties = new Properties();
    properties.setProperty("activeId", manifest.activeId().toString());
    properties.setProperty("walLineage", manifest.walLineage().toString());
    properties.setProperty("boundaryLsn", Long.toString(manifest.boundaryLsn().value()));
    properties.setProperty("highWaterLsn", Long.toString(manifest.highWater().lsn().value()));
    properties.setProperty("maxTx", Long.toString(manifest.highWater().maxTx().value()));
    properties.setProperty("maxTimestamp", Long.toString(manifest.highWater().maxTimestamp()));
    manifest
        .highWater()
        .maxCommitTime()
        .ifPresent(value -> properties.setProperty("maxCommitTime", value.toString()));
    return properties;
  }

  private static GenerationManifest fromProperties(Properties properties) {
    UUID activeId = UUID.fromString(required(properties, "activeId"));
    UUID lineage = UUID.fromString(required(properties, "walLineage"));
    LogSequenceNumber boundary =
        new LogSequenceNumber(Long.parseLong(required(properties, "boundaryLsn")));
    RecoveryHighWater highWater =
        new RecoveryHighWater(
            new LogSequenceNumber(Long.parseLong(required(properties, "highWaterLsn"))),
            new TransactionId(Long.parseLong(required(properties, "maxTx"))),
            Long.parseLong(required(properties, "maxTimestamp")),
            Optional.ofNullable(properties.getProperty("maxCommitTime")).map(Instant::parse));
    return new GenerationManifest(activeId, lineage, boundary, highWater);
  }

  private static String required(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing manifest property: " + key);
    }
    return value;
  }
}
