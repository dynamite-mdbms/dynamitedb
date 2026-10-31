package dynamitedb.contracts;

/** Maintenance lease. Closing a lease releases admission according to the CCM contract. */
public interface MaintenanceGuard extends AutoCloseable {
  @Override
  void close();
}
