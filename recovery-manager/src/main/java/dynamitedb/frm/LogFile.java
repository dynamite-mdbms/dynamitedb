package dynamitedb.frm;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32;

/** Single-writer WAL file. Every frame has a complete length and checksum. */
public final class LogFile implements AutoCloseable {
  private static final int FILE_MAGIC = 0x4457414C;
  private static final int VERSION = 1;
  private static final int HEADER_BYTES = 36;
  private final MockRecoveryContext mock;
  private final FileChannel channel;
  private final FileLock lock;
  private final boolean readOnly;
  private final long floor;
  private boolean broken;

  public LogFile(MockRecoveryContext context) {
    this.mock = Objects.requireNonNull(context, "context");
    this.channel = null;
    this.lock = null;
    this.floor = 0;
    this.readOnly = false;
  }

  private LogFile(Path root, UUID lineage, long floor, boolean create, boolean readOnly) {
    this.mock = null;
    this.readOnly = readOnly;
    if (floor < 0) throw new IllegalArgumentException("Negative WAL floor");
    FileChannel opened = null;
    FileLock acquired = null;
    try {
      if (create) Files.createDirectories(root);
      Path path = path(root, lineage);
      opened =
          create
              ? FileChannel.open(
                  path,
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE)
              : readOnly
                  ? FileChannel.open(path, StandardOpenOption.READ)
                  : FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
      if (!readOnly) {
        acquired = opened.tryLock();
        if (acquired == null)
          throw new DbmsException(ErrorCode.WAL_FAILURE, "WAL already has a writer: " + path);
      }
      this.channel = opened;
      this.lock = acquired;
      if (create) {
        this.floor = floor;
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(FILE_MAGIC).putInt(VERSION);
        header.putLong(lineage.getMostSignificantBits()).putLong(lineage.getLeastSignificantBits());
        header.putLong(floor);
        byte[] prefix = new byte[HEADER_BYTES - 4];
        header.flip();
        header.get(prefix);
        var complete = ByteBuffer.allocate(HEADER_BYTES);
        complete.put(prefix).putInt(crc(prefix)).flip();
        writeAll(complete, 0);
        channel.force(true);
      } else {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        if (!readAll(header, 0)) throw corrupt("Truncated WAL header");
        byte[] data = header.array();
        int expected = ByteBuffer.wrap(data, HEADER_BYTES - 4, 4).getInt();
        byte[] prefix = java.util.Arrays.copyOf(data, HEADER_BYTES - 4);
        if (expected != crc(prefix)) throw corrupt("WAL header checksum mismatch");
        header.flip();
        if (header.getInt() != FILE_MAGIC || header.getInt() != VERSION)
          throw corrupt("Unsupported WAL header");
        UUID stored = new UUID(header.getLong(), header.getLong());
        if (!stored.equals(lineage)) throw corrupt("WAL lineage mismatch");
        this.floor = header.getLong();
        if (this.floor < 0 || this.floor != floor) throw corrupt("WAL floor mismatch");
      }
    } catch (IOException failure) {
      releaseOnFailure(acquired, opened);
      throw new DbmsException(ErrorCode.WAL_FAILURE, "Cannot open WAL: " + failure.getMessage());
    } catch (java.nio.channels.OverlappingFileLockException failure) {
      releaseOnFailure(acquired, opened);
      throw new DbmsException(ErrorCode.WAL_FAILURE, "WAL already has a writer");
    } catch (RuntimeException failure) {
      releaseOnFailure(acquired, opened);
      throw failure;
    }
  }

  public static Path path(Path root, UUID lineage) {
    return Objects.requireNonNull(root, "root")
        .resolve("wal-" + Objects.requireNonNull(lineage, "lineage") + ".bin");
  }

  public static LogFile createNew(Path root, UUID lineage, long floor) {
    return new LogFile(root, lineage, floor, true, false);
  }

  public static LogFile openExisting(Path root, UUID lineage, long floor) {
    return new LogFile(root, lineage, floor, false, false);
  }

  public static LogFile openHistory(Path root, UUID lineage, long floor) {
    return new LogFile(root, lineage, floor, false, true);
  }

  public long floor() {
    return floor;
  }

  public synchronized void appendFrame(ImmutableBytes frame) {
    Objects.requireNonNull(frame, "frame");
    if (mock != null) {
      mock.invoked("LogFile.appendFrame");
      return;
    }
    requireWritable();
    byte[] bytes = frame.copy();
    if (bytes.length < 16 || bytes.length > LogRecordCodec.MAX_PAYLOAD + 16) {
      throw new IllegalArgumentException("Invalid WAL frame size");
    }
    try {
      writeAll(ByteBuffer.wrap(bytes), channel.size());
    } catch (IOException failure) {
      broken = true;
      throw new DbmsException(ErrorCode.WAL_FAILURE, "WAL append failed: " + failure.getMessage());
    }
  }

  public synchronized void sync() {
    if (mock != null) {
      mock.invoked("LogFile.sync");
      return;
    }
    requireWritable();
    try {
      channel.force(true);
    } catch (IOException failure) {
      broken = true;
      throw new DbmsException(ErrorCode.WAL_FAILURE, "WAL force failed: " + failure.getMessage());
    }
  }

  public synchronized Iterator<ImmutableBytes> readFrames() {
    if (mock != null) {
      mock.invoked("LogFile.readFrames");
      return List.of(mock.frame()).iterator();
    }
    return scan(false).frames.iterator();
  }

  synchronized Iterator<ImmutableBytes> readRecoverableFrames() {
    if (mock != null) return readFrames();
    return scan(true).frames.iterator();
  }

  public synchronized void repairIncompleteTail() {
    if (mock != null) {
      mock.invoked("LogFile.repairIncompleteTail");
      return;
    }
    if (readOnly)
      throw new DbmsException(ErrorCode.INVALID_STATE, "Historical WAL cannot be repaired");
    Scan result = scan(true);
    if (!result.incomplete) return;
    try {
      channel.truncate(result.end);
      channel.force(true);
      broken = false;
    } catch (IOException failure) {
      broken = true;
      throw new DbmsException(
          ErrorCode.WAL_FAILURE, "Cannot repair WAL tail: " + failure.getMessage());
    }
  }

  private Scan scan(boolean allowTail) {
    try {
      long size = channel.size();
      long offset = HEADER_BYTES;
      var frames = new ArrayList<ImmutableBytes>();
      while (offset < size) {
        if (size - offset < 12) return tail(allowTail, frames, offset);
        ByteBuffer header = ByteBuffer.allocate(12);
        if (!readAll(header, offset)) return tail(allowTail, frames, offset);
        header.flip();
        if (header.getInt() != LogRecordCodec.MAGIC)
          throw corrupt("Invalid WAL frame at " + offset);
        int length = header.getInt();
        if (header.getInt() != LogRecordCodec.headerChecksum(length))
          throw corrupt("WAL frame header checksum mismatch at " + offset);
        if (length < 0 || length > LogRecordCodec.MAX_PAYLOAD)
          throw corrupt("Invalid frame size at " + offset);
        long total = 16L + length;
        if (size - offset < total) return tail(allowTail, frames, offset);
        ByteBuffer frame = ByteBuffer.allocate((int) total);
        if (!readAll(frame, offset)) return tail(allowTail, frames, offset);
        byte[] data = frame.array();
        int recorded = ByteBuffer.wrap(data, (int) total - 4, 4).getInt();
        var payload = java.util.Arrays.copyOfRange(data, 12, 12 + length);
        if (recorded != crc(payload)) throw corrupt("WAL checksum mismatch at " + offset);
        frames.add(ImmutableBytes.copyOf(data));
        offset += total;
      }
      return new Scan(frames, offset, false);
    } catch (IOException failure) {
      throw new DbmsException(ErrorCode.WAL_FAILURE, "Cannot read WAL: " + failure.getMessage());
    }
  }

  private static Scan tail(boolean allowed, List<ImmutableBytes> frames, long offset) {
    if (!allowed) throw corrupt("Incomplete WAL tail at " + offset);
    return new Scan(frames, offset, true);
  }

  private boolean readAll(ByteBuffer buffer, long offset) throws IOException {
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, offset);
      if (read < 0) return false;
      offset += read;
    }
    return true;
  }

  private void writeAll(ByteBuffer buffer, long offset) throws IOException {
    while (buffer.hasRemaining()) offset += channel.write(buffer, offset);
  }

  private void requireWritable() {
    if (readOnly || broken || !channel.isOpen())
      throw new DbmsException(ErrorCode.WAL_FAILURE, "WAL writer requires recovery");
  }

  @Override
  public synchronized void close() {
    if (mock != null) return;
    if (!channel.isOpen()) return;
    IOException failure = null;
    try {
      if (lock != null) lock.release();
    } catch (IOException releaseFailure) {
      failure = releaseFailure;
    }
    try {
      channel.close();
    } catch (IOException closeFailure) {
      if (failure == null) failure = closeFailure;
      else failure.addSuppressed(closeFailure);
    }
    if (failure != null)
      throw new DbmsException(ErrorCode.WAL_FAILURE, "Cannot close WAL: " + failure.getMessage());
  }

  private static int crc(byte[] bytes) {
    var c = new CRC32();
    c.update(bytes);
    return (int) c.getValue();
  }

  private static void releaseOnFailure(FileLock lock, FileChannel channel) {
    try {
      if (lock != null) lock.release();
    } catch (IOException ignored) {
      /* Retain original error. */
    }
    try {
      if (channel != null) channel.close();
    } catch (IOException ignored) {
      /* Retain original error. */
    }
  }

  private static DbmsException corrupt(String message) {
    return new DbmsException(ErrorCode.CORRUPT_LOG, message);
  }

  private record Scan(List<ImmutableBytes> frames, long end, boolean incomplete) {}
}
