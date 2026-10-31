package dynamitedb.contracts.api;

public interface MaintenanceGuard extends AutoCloseable {
  @Override
  void close();
}
