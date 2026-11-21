package dynamitedb.contracts.api;

public interface MaintenanceGuard extends AutoCloseable {
  /** On an incomplete publication, keep a live runtime fenced even after closing this lease. */
  default void requireRestart() {}

  @Override
  void close();
}
