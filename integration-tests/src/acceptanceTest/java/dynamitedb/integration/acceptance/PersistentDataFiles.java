package dynamitedb.integration.acceptance;

import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.frm.CheckpointEvent;
import dynamitedb.frm.CheckpointLogRecord;
import dynamitedb.frm.LogFile;
import dynamitedb.frm.LogRecord;
import dynamitedb.frm.LogRecordCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

final class PersistentDataFiles {
  private static final String MANIFEST_FILE = "active-generation.properties";
  private static final long WAL_HEADER_BYTES = 36;

  private PersistentDataFiles() {}

  static Path activeWal(Path dataDirectory) throws IOException {
    Properties manifest = readManifest(dataDirectory);
    return LogFile.path(dataDirectory, UUID.fromString(manifest.getProperty("walLineage")));
  }

  static long checkpointEventCount(Path dataDirectory, CheckpointEvent event) throws IOException {
    Properties manifest = readManifest(dataDirectory);
    UUID lineage = UUID.fromString(manifest.getProperty("walLineage"));
    long floor = Long.parseLong(manifest.getProperty("boundaryLsn"));
    long count = 0;
    try (LogFile file = LogFile.openHistory(dataDirectory, lineage, floor)) {
      var frames = file.readFrames();
      var codec = new LogRecordCodec();
      while (frames.hasNext()) {
        ImmutableBytes frame = frames.next();
        LogRecord record = codec.decode(frame);
        if (record instanceof CheckpointLogRecord checkpoint && checkpoint.event == event) count++;
      }
    }
    return count;
  }

  static void assertWalComplete(Path dataDirectory) throws IOException {
    Properties manifest = readManifest(dataDirectory);
    UUID lineage = UUID.fromString(manifest.getProperty("walLineage"));
    long floor = Long.parseLong(manifest.getProperty("boundaryLsn"));
    try (LogFile file = LogFile.openHistory(dataDirectory, lineage, floor)) {
      var frames = file.readFrames();
      var codec = new LogRecordCodec();
      while (frames.hasNext()) codec.decode(frames.next());
    }
  }

  static void corruptMiddleFrame(Path dataDirectory) throws IOException {
    Path wal = activeWal(dataDirectory);
    List<byte[]> frames = new ArrayList<>();
    Properties manifest = readManifest(dataDirectory);
    UUID lineage = UUID.fromString(manifest.getProperty("walLineage"));
    long floor = Long.parseLong(manifest.getProperty("boundaryLsn"));
    try (LogFile file = LogFile.openHistory(dataDirectory, lineage, floor)) {
      var iterator = file.readFrames();
      while (iterator.hasNext()) frames.add(iterator.next().copy());
    }
    if (frames.size() < 3)
      throw new IOException("WAL needs multiple frames for a middle-corruption test");
    int middleFrame = frames.size() / 2;
    long position = WAL_HEADER_BYTES;
    for (int index = 0; index < middleFrame; index++) position += frames.get(index).length;
    int payloadBytes = frames.get(middleFrame).length - 16;
    if (payloadBytes <= 0) throw new IOException("Middle WAL frame has an empty payload");
    position += 12 + payloadBytes / 2;
    try (FileChannel channel =
        FileChannel.open(wal, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      if (channel.size() <= position)
        throw new IOException("WAL has no complete record to corrupt");
      ByteBuffer value = ByteBuffer.allocate(1);
      if (channel.read(value, position) != 1) throw new IOException("Cannot read WAL byte");
      value.flip();
      value.put(0, (byte) (value.get(0) ^ 0x40));
      value.position(0);
      channel.write(value, position);
      channel.force(true);
    }
  }

  private static Properties readManifest(Path dataDirectory) throws IOException {
    Path file = dataDirectory.resolve(MANIFEST_FILE);
    Properties manifest = new Properties();
    try (var input = Files.newInputStream(file)) {
      manifest.load(input);
    }
    if (manifest.getProperty("walLineage") == null || manifest.getProperty("boundaryLsn") == null) {
      throw new IOException("Active generation manifest is incomplete: " + file);
    }
    return manifest;
  }
}
