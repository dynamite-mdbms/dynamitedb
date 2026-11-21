package dynamitedb.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.transaction.ProtocolType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TransactionAcceptanceTest extends PackagedServerAcceptanceSupport {
  @ParameterizedTest
  @EnumSource(ProtocolType.class)
  void realTcpServerExecutesDmlCommitAbortRepeatedUpdatesAndPreparationErrors(ProtocolType protocol)
      throws Exception {
    try (PackagedServerProcess server = startServer(protocol);
        AcceptanceClient client = connect(server)) {
      client.execute("CREATE TABLE acceptance_tx (id INTEGER PRIMARY KEY, value VARCHAR(32));");

      assertAffected(client, "INSERT INTO acceptance_tx VALUES (1, 'initial');", 1);
      client.execute("BEGIN;");
      assertAffected(client, "UPDATE acceptance_tx SET value = 'first' WHERE id = 1;", 1);
      assertAffected(client, "UPDATE acceptance_tx SET value = 'committed' WHERE id = 1;", 1);
      assertEquals("committed", onlyText(client, "SELECT value FROM acceptance_tx WHERE id = 1;"));
      client.execute("COMMIT;");
      assertEquals("committed", onlyText(client, "SELECT value FROM acceptance_tx WHERE id = 1;"));

      client.execute("BEGIN;");
      assertAffected(client, "INSERT INTO acceptance_tx VALUES (2, 'temporary');", 1);
      assertAffected(client, "UPDATE acceptance_tx SET value = 'aborted' WHERE id = 2;", 1);
      assertAffected(client, "UPDATE acceptance_tx SET value = 'aborted-again' WHERE id = 2;", 1);
      client.execute("ABORT;");
      assertNoRows(client, "SELECT id FROM acceptance_tx WHERE id = 2;");

      client.execute("BEGIN;");
      assertAffected(client, "INSERT INTO acceptance_tx VALUES (3, 'before-error');", 1);
      DbmsException preparationFailure =
          assertThrows(
              DbmsException.class,
              () -> client.execute("SELECT missing_column FROM acceptance_tx;"));
      assertEquals(ErrorCode.SQL_BIND, preparationFailure.getCode());
      client.execute("COMMIT;");
      assertEquals(
          "before-error", onlyText(client, "SELECT value FROM acceptance_tx WHERE id = 3;"));

      assertAffected(client, "UPDATE acceptance_tx SET value = 'updated' WHERE id = 3;", 1);
      assertAffected(client, "UPDATE acceptance_tx SET value = 'absent' WHERE id = 99;", 0);
      assertAffected(client, "DELETE FROM acceptance_tx WHERE id = 3;", 1);
      assertAffected(client, "DELETE FROM acceptance_tx WHERE id = 3;", 0);
      assertNoRows(client, "SELECT id FROM acceptance_tx WHERE id = 3;");
      assertEquals(
          new TextValue("committed"),
          onlyRow(client, "SELECT value FROM acceptance_tx WHERE id = 1;").values().getFirst());
    }
  }
}
