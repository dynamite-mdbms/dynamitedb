package dynamitedb.contracts;

/** Read-only filtered WAL inspection; internal log records never cross this port. */
public interface LogInspection {
  LogView readLog(LogFilter filter);
}
