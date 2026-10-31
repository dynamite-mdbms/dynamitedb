package dynamitedb.sm.buffer;

import dynamitedb.contracts.storage.ImmutableBytes;
import java.nio.file.Path;
import java.util.Objects;

/** Membaca dan menulis byte halaman ke disk (SM-01). */
public final class DiskManager {
  private final Path root;

  public DiskManager(Path root) {
    this.root = Objects.requireNonNull(root, "root");
  }

  public ImmutableBytes readPage(long id) {
    // TODO: sepakati ukuran halaman dan pemetaan file dengan SM3
    return ImmutableBytes.copyOf(new byte[0]);
  }

  public void writePage(long id, ImmutableBytes bytes) {
    // TODO: tulis halaman utuh, gerbang WAL sudah dipenuhi BufferPool
  }

  public void sync() {
    // TODO: sinkronkan file halaman ke disk
  }
}
