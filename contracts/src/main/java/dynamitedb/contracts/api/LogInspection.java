package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.LogFilter;
import dynamitedb.contracts.recovery.LogView;

/** Read-only filtered WAL inspection; internal log records never cross this port. */
public interface LogInspection {
  LogView readLog(LogFilter filter);
}
