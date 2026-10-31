package dynamitedb.sm.buffer;

import dynamitedb.sm.draft.ImmutableBytes;
import java.nio.file.Path;
import java.util.Objects;

/** SM2 / Raynard Fausta: page-byte I/O and durable synchronization (SM-01, SM-12-SM-13). */
public final class DiskManager {
  private final Path root;

  public DiskManager(Path root) {
    this.root = Objects.requireNonNull(root, "root");
  }

  public ImmutableBytes readPage(long id) {
    // TODO(SM2/SM3): Agree on page size, file mapping, missing-page handling and short-read checks.
    throw new UnsupportedOperationException("TODO(SM2): read page bytes");
  }

  public void writePage(long id, ImmutableBytes bytes) {
    // TODO(SM2): Write the complete page. BufferPool must already have satisfied WAL durability.
    throw new UnsupportedOperationException("TODO(SM2): write page bytes");
  }

  public void sync() {
    // TODO(SM2): Synchronize page files and required directory metadata before returning success.
    throw new UnsupportedOperationException("TODO(SM2): synchronize storage");
  }
}
