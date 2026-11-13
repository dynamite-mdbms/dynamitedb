package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.catalog.SampleDatabaseLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Ngecek perhitungan QP2 lewat query asli
class QP2IntegrationTest {

  @TempDir Path directory;

  private StubRuntime runtime;
  private SessionId session;

  @BeforeEach
  void start() throws Exception {
    DbmsConfig config = new DbmsConfig(0, directory, DbmsConfig.defaults().protocol());
    runtime = new StubRuntime(config);
    runtime.bootstrap().start(config);
    var ccm = runtime.concurrency();
    new SampleDatabaseLoader(runtime.storage(), ccm, ccm).loadIfAbsent(runtime.catalog());
    session = runtime.processor().openSession("qp2");
  }

  @AfterEach
  void stop() {
    runtime.processor().closeSession(session);
    runtime.bootstrap().shutdown();
  }

  @Test
  void overflowInWhereIsRejected() {
    String query = "SELECT student_id FROM student WHERE 2147483647 + 1 < 0";

    DbmsException error = assertThrows(DbmsException.class, () -> run(query));

    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
  }

  @Test
  void overflowInUpdateKeepsData() {
    String query = "UPDATE course SET credits = credits * 2147483647";
    List<Row> before = courses();

    DbmsException error = assertThrows(DbmsException.class, () -> run(query));

    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
    assertEquals(before, courses());
  }

  private void run(String query) {
    runtime.processor().execute(session, query);
  }

  private List<Row> courses() {
    List<Row> rows = new ArrayList<>();
    try (RowCursor cursor = runtime.storage().openTableScan("course", new TransactionId(1))) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        rows.add(next.get().row());
      }
    }
    return rows;
  }
}
