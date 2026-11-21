package dynamitedb.app;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** Keeps one runtime in the root even when PITR changes the locked WAL file. */
final class RuntimeRootLock implements AutoCloseable {
  private static final String NAME = ".runtime.lock";
  private static final Set<Path> OWNED = new HashSet<>();

  private final Path root;
  private final FileChannel channel;

  private RuntimeRootLock(Path root, FileChannel channel) {
    this.root = root;
    this.channel = channel;
  }

  @SuppressWarnings("try")
  static synchronized RuntimeRootLock acquire(Path data) {
    try {
      Path root = Files.createDirectories(data).toRealPath();
      if (!OWNED.add(root)) throw inUse(root);
      FileChannel channel = null;
      try {
        channel =
            FileChannel.open(
                root.resolve(NAME),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        if (channel.tryLock() == null) throw inUse(root);
        return new RuntimeRootLock(root, channel);
      } catch (IOException | RuntimeException | Error failure) {
        OWNED.remove(root);
        try (var opened = channel) {
          throw failure;
        }
      }
    } catch (IOException failure) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Cannot lock database root: " + data, failure);
    }
  }

  static boolean isLockFile(Path path) {
    if (!NAME.equals(path.getFileName().toString())
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
    try {
      return Files.size(path) == 0;
    } catch (IOException failure) {
      return false;
    }
  }

  @Override
  public void close() {
    synchronized (RuntimeRootLock.class) {
      if (!channel.isOpen()) return;
      try {
        // Keep the lock inode in place for other processes.
        channel.close();
      } catch (IOException failure) {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE, "Cannot unlock database root: " + root, failure);
      } finally {
        OWNED.remove(root);
      }
    }
  }

  private static DbmsException inUse(Path root) {
    return new DbmsException(ErrorCode.STORAGE_FAILURE, "Database root already in use: " + root);
  }
}
