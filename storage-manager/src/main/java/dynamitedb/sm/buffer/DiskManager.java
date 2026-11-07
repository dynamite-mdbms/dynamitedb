package dynamitedb.sm.buffer;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.ImmutableBytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.zip.CRC32;

public final class DiskManager {
  public static final int PAGE_SIZE = 4096;
  private static final int MAGIC = 0x44595047;
  private static final int PENDING_MAGIC = 0x4459504a;
  private static final int INITIALIZED_MAGIC = 0x44595049;
  private static final int VERSION = 1;
  private static final int HEADER_SIZE = 16;
  private static final int STORED_PAGE_SIZE = HEADER_SIZE + PAGE_SIZE;
  private static final int JOURNAL_SIZE = 16 + STORED_PAGE_SIZE + Long.BYTES;
  private static final Object[] IO_LOCKS =
      java.util.stream.IntStream.range(0, 64).mapToObj(index -> new Object()).toArray();
  private final Path root;
  private final Path file;
  private final Path pending;
  private boolean seenData;

  public DiskManager(Path root) {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    file = this.root.resolve("pages.db");
    pending = this.root.resolve("pages.pending");
  }

  public ImmutableBytes readPage(long id) {
    return loadPage(id).getData();
  }

  public Page loadPage(long id) {
    long start = offset(id);
    return locked(
        () -> {
          if (!Files.exists(file)) {
            throw failure("Halaman tidak ada: " + id);
          }
          try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (id >= pageCount(channel)) {
              throw failure("Halaman tidak ada: " + id);
            }
            var block = ByteBuffer.allocate(STORED_PAGE_SIZE);
            readFully(channel, block, start);
            block.flip();
            validateHeader(block);
            long lsn = block.getLong();
            if (lsn < 0) {
              throw failure("pageLSN negatif pada halaman " + id);
            }
            byte[] payload = new byte[PAGE_SIZE];
            block.get(payload);
            return new Page(id, ImmutableBytes.copyOf(payload), new LogSequenceNumber(lsn));
          }
        });
  }

  public void writePage(long id, ImmutableBytes bytes) {
    offset(id);
    byte[] payload = validatePayload(bytes);
    locked(
        () -> {
          long lsn = 0;
          if (id < countExistingPages()) {
            try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
              var header = ByteBuffer.allocate(HEADER_SIZE);
              readFully(channel, header, offset(id));
              header.flip();
              validateHeader(header);
              lsn = header.getLong();
              if (lsn < 0) {
                throw failure("pageLSN negatif pada halaman " + id);
              }
            }
          }
          store(id, payload, lsn);
          return null;
        });
  }

  public void writePage(long id, ImmutableBytes bytes, LogSequenceNumber lsn) {
    offset(id);
    byte[] payload = validatePayload(bytes);
    if (Objects.requireNonNull(lsn, "lsn").value() < 0) {
      throw failure("pageLSN tidak boleh negatif");
    }
    locked(
        () -> {
          store(id, payload, lsn.value());
          return null;
        });
  }

  public long appendPage() {
    return appendPage(ImmutableBytes.copyOf(new byte[PAGE_SIZE]));
  }

  public long appendPage(ImmutableBytes bytes) {
    byte[] payload = validatePayload(bytes);
    return locked(
        () -> {
          long id = countExistingPages();
          store(id, payload, 0);
          return id;
        });
  }

  public long getPageCount() {
    return locked(this::countExistingPages);
  }

  public void sync() {
    locked(
        () -> {
          if (Files.exists(file)) {
            try (var channel = openWriter(file)) {
              pageCount(channel);
              channel.force(true);
            }
          }
          return null;
        });
  }

  private void store(long id, byte[] payload, long lsn) throws IOException {
    if (id > countExistingPages()) {
      throw failure("Penulisan halaman tidak boleh membuat celah: " + id);
    }
    var block = ByteBuffer.allocate(STORED_PAGE_SIZE);
    block.putInt(MAGIC).putInt(VERSION).putLong(lsn).put(payload);
    var journal = ByteBuffer.allocate(JOURNAL_SIZE);
    journal.putInt(PENDING_MAGIC).putInt(VERSION).putLong(id).put(block.array());
    var checksum = new CRC32();
    checksum.update(journal.array(), 0, JOURNAL_SIZE - Long.BYTES);
    journal.putLong(checksum.getValue()).flip();
    try (var channel = openWriter(pending)) {
      channel.truncate(0);
      writeFully(channel, journal, 0);
      channel.force(true);
    }
    writeBlock(id, block.array());
    clearPending();
  }

  private void recoverPending() throws IOException {
    if (Files.notExists(pending)) {
      return;
    }
    var journal = ByteBuffer.allocate(JOURNAL_SIZE);
    try (var channel = FileChannel.open(pending, StandardOpenOption.READ)) {
      long size = channel.size();
      if (size == 0) {
        return;
      }
      if (size < JOURNAL_SIZE) {
        journal.limit(0);
      } else {
        if (size != JOURNAL_SIZE) {
          throw failure("Ukuran redo journal halaman rusak");
        }
        readFully(channel, journal, 0);
        journal.flip();
      }
    }
    if (!journal.hasRemaining()) {
      clearPending();
      return;
    }
    var checksum = new CRC32();
    checksum.update(journal.array(), 0, JOURNAL_SIZE - Long.BYTES);
    if (journal.getLong(JOURNAL_SIZE - Long.BYTES) != checksum.getValue()
        || journal.getInt() != PENDING_MAGIC
        || journal.getInt() != VERSION) {
      throw failure("Checksum atau header redo journal halaman rusak");
    }
    long id = journal.getLong();
    offset(id);
    byte[] block = new byte[STORED_PAGE_SIZE];
    journal.get(block);
    var header = ByteBuffer.wrap(block);
    validateHeader(header);
    if (header.getLong() < 0) {
      throw failure("pageLSN redo journal tidak valid");
    }
    writeBlock(id, block);
    clearPending();
  }

  private void writeBlock(long id, byte[] block) throws IOException {
    long start = offset(id);
    try (var channel = openWriter(file)) {
      long size = channel.size();
      if (size < start || (size % STORED_PAGE_SIZE != 0 && size / STORED_PAGE_SIZE != id)) {
        throw failure("Redo journal tidak cocok dengan panjang file halaman");
      }
      writeFully(channel, ByteBuffer.wrap(block), start);
      channel.force(true);
      seenData = true;
    }
  }

  private void clearPending() throws IOException {
    try (var channel = openWriter(pending)) {
      channel.truncate(0);
      channel.force(true);
    }
  }

  private long countExistingPages() throws IOException {
    if (Files.notExists(file)) {
      return 0;
    }
    try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
      return pageCount(channel);
    }
  }

  private <T> T locked(Callable<T> operation) {
    try {
      Files.createDirectories(root);
      Path physicalRoot = root.toRealPath();
      Object monitor = IO_LOCKS[Math.floorMod(physicalRoot.hashCode(), IO_LOCKS.length)];
      synchronized (monitor) {
        try (var lockChannel =
                FileChannel.open(
                    root.resolve("pages.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            var guard = lockChannel.lock()) {
          if (!guard.isValid()) {
            throw failure("Lock file halaman tidak valid");
          }
          boolean initialized = hasInitializedHeap(lockChannel);
          if ((seenData || initialized) && Files.notExists(file)) {
            throw failure("File halaman yang sudah dibuka hilang: " + file);
          }
          if (Files.exists(file)) {
            if (!Files.isRegularFile(file)) {
              throw failure("File halaman bukan regular file: " + file);
            }
            seenData = true;
          }
          recoverPending();
          T result = operation.call();
          if (lockChannel.size() < Long.BYTES && Files.exists(file)) {
            var marker = ByteBuffer.allocate(Long.BYTES);
            marker.putInt(INITIALIZED_MAGIC).putInt(VERSION).flip();
            writeFully(lockChannel, marker, 0);
            lockChannel.force(true);
          }
          return result;
        }
      }
    } catch (DbmsException exception) {
      throw exception;
    } catch (IOException | OverlappingFileLockException exception) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "I/O halaman gagal: " + file, exception);
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Operasi halaman gagal: " + file, exception);
    }
  }

  private static boolean hasInitializedHeap(FileChannel lockChannel) throws IOException {
    long size = lockChannel.size();
    if (size == 0) {
      return false;
    }
    if (size > Long.BYTES) {
      throw failure("Marker inisialisasi storage rusak");
    }
    var marker = ByteBuffer.allocate((int) size);
    readFully(lockChannel, marker, 0);
    byte[] expected =
        ByteBuffer.allocate(Long.BYTES).putInt(INITIALIZED_MAGIC).putInt(VERSION).array();
    for (int index = 0; index < size; index++) {
      if (marker.array()[index] != expected[index]) {
        throw failure("Marker inisialisasi storage rusak");
      }
    }
    return true;
  }

  private static FileChannel openWriter(Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
  }

  private static void readFully(FileChannel channel, ByteBuffer target, long start)
      throws IOException {
    while (target.hasRemaining()) {
      if (channel.read(target, start + target.position()) <= 0) {
        throw new IOException("Pembacaan block terpotong");
      }
    }
  }

  private static void writeFully(FileChannel channel, ByteBuffer source, long start)
      throws IOException {
    while (source.hasRemaining()) {
      if (channel.write(source, start + source.position()) <= 0) {
        throw new IOException("Penulisan block tidak lengkap");
      }
    }
  }

  private static void validateHeader(ByteBuffer header) {
    if (header.getInt() != MAGIC || header.getInt() != VERSION) {
      throw failure("Header halaman rusak atau versi tidak didukung");
    }
  }

  private static long pageCount(FileChannel channel) throws IOException {
    long size = channel.size();
    if (size % STORED_PAGE_SIZE != 0) {
      throw failure("File halaman terpotong: " + size + " byte");
    }
    return size / STORED_PAGE_SIZE;
  }

  private static long offset(long id) {
    if (id < 0 || id > (Long.MAX_VALUE - STORED_PAGE_SIZE) / STORED_PAGE_SIZE) {
      throw failure("ID halaman tidak valid: " + id);
    }
    return id * STORED_PAGE_SIZE;
  }

  private static byte[] validatePayload(ImmutableBytes bytes) {
    byte[] payload = Objects.requireNonNull(bytes, "bytes").copy();
    if (payload.length != PAGE_SIZE) {
      throw failure("Ukuran payload halaman harus " + PAGE_SIZE + " byte");
    }
    return payload;
  }

  private static DbmsException failure(String message) {
    return new DbmsException(ErrorCode.STORAGE_FAILURE, message);
  }
}
