package dynamitedb.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.ProtocolType;
import org.junit.jupiter.api.Test;

class TimestampAcceptanceTest extends PackagedServerAcceptanceSupport {
  @Test
  void staleTimestampAbortsAndRollsBackEarlierWrites() throws Exception {
    try (PackagedServerProcess server = startServer(ProtocolType.STRICT_TIMESTAMP);
        AcceptanceClient old = connect(server);
        AcceptanceClient newer = connect(server);
        AcceptanceClient observer = connect(server)) {
      old.execute("CREATE TABLE timestamp_left (id INTEGER PRIMARY KEY, value VARCHAR(32));");
      old.execute("CREATE TABLE timestamp_right (id INTEGER PRIMARY KEY, value VARCHAR(32));");
      old.execute("INSERT INTO timestamp_left VALUES (1, 'left-before');");
      old.execute("INSERT INTO timestamp_right VALUES (1, 'right-before');");

      old.execute("BEGIN;");
      old.execute("UPDATE timestamp_left SET value = 'old-uncommitted' WHERE id = 1;");
      old.execute("SELECT value FROM timestamp_right WHERE id = 1;");

      newer.execute("BEGIN;");
      newer.execute("UPDATE timestamp_right SET value = 'new-committed' WHERE id = 1;");
      newer.execute("COMMIT;");

      DbmsException staleAbort =
          assertThrows(
              DbmsException.class,
              () -> old.execute("UPDATE timestamp_right SET value = 'stale-write' WHERE id = 1;"));
      assertEquals(ErrorCode.ACCESS_ABORTED, staleAbort.getCode());
      assertEquals(
          "left-before", onlyText(observer, "SELECT value FROM timestamp_left WHERE id = 1;"));
      assertEquals(
          "new-committed", onlyText(observer, "SELECT value FROM timestamp_right WHERE id = 1;"));
    }
  }
}
