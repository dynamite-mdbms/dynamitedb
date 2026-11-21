package dynamitedb.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.transaction.ProtocolType;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class LimitAcceptanceTest extends PackagedServerAcceptanceSupport {
  @Test
  void limitPreservesQuerySemanticsAndRejectsInvalidValuesOnTheRealServer() throws Exception {
    try (PackagedServerProcess server = startServer(ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(server)) {
      QueryResult allRows = client.execute("SELECT student_id FROM student;");
      QueryResult noRows = client.execute("SELECT student_id FROM student LIMIT 0;");
      assertEquals(allRows.schema(), noRows.schema());
      assertTrue(noRows.rows().isEmpty());

      assertEquals(1, client.execute("SELECT student_id FROM student LIMIT 1;").rows().size());
      assertEquals(
          allRows.rows(), client.execute("SELECT student_id FROM student LIMIT 1000;").rows());

      QueryResult empty =
          client.execute("SELECT student_id FROM student WHERE student_id = -1 LIMIT 1;");
      assertEquals(allRows.schema(), empty.schema());
      assertTrue(empty.rows().isEmpty());

      assertEquals(
          2,
          client
              .execute(
                  "SELECT student.student_id FROM student JOIN enrollment"
                      + " ON student.student_id = enrollment.student_id LIMIT 2;")
              .rows()
              .size());
      assertEquals(
          List.of(new IntValue(1), new IntValue(2)),
          client
              .execute("SELECT student_id FROM student ORDER BY student_id ASC LIMIT 2;")
              .rows()
              .stream()
              .map(row -> row.values().getFirst())
              .toList());
      assertEquals(
          List.of(new IntValue(50), new IntValue(49)),
          client
              .execute("SELECT student_id FROM student ORDER BY student_id DESC LIMIT 2;")
              .rows()
              .stream()
              .map(row -> row.values().getFirst())
              .toList());
      assertEquals(
          49,
          onlyInt(
              client,
              "SELECT student_id FROM student WHERE student_id >= 49"
                  + " ORDER BY student_id ASC LIMIT 1;"));

      assertSyntaxError(client, "SELECT student_id FROM student LIMIT;");
      assertSyntaxError(client, "SELECT student_id FROM student LIMIT -1;");
      assertSyntaxError(client, "SELECT student_id FROM student LIMIT 1.5;");
      assertSyntaxError(client, "SELECT student_id FROM student LIMIT 9223372036854775808;");
    }
  }

  private static void assertSyntaxError(AcceptanceClient client, String sql) throws IOException {
    DbmsException failure = assertThrows(DbmsException.class, () -> client.execute(sql), sql);
    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode(), sql);
  }
}
