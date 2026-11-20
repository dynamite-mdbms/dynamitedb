package dynamitedb.integration.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.query.PlanExplanation;
import dynamitedb.contracts.transaction.ProtocolType;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IndexAcceptanceTest extends PackagedServerAcceptanceSupport {
  @Test
  void hashAndBtreeIndexesStayCorrectAcrossDmlRollbackRestartAndUseIndexedPlans() throws Exception {
    Path data = directory.resolve("index-data");
    try (PackagedServerProcess server = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(server)) {
      client.execute(
          "CREATE TABLE acceptance_index_rows (id INTEGER, hash_key VARCHAR(32), tree_key INTEGER);");
      client.execute("BEGIN;");
      for (int id = 1; id <= 400; id++) {
        assertAffected(
            client,
            "INSERT INTO acceptance_index_rows VALUES ("
                + id
                + ", 'hash-"
                + id
                + "', "
                + (10_000 + id)
                + ");",
            1);
      }
      client.execute("COMMIT;");
      client.execute(
          "CREATE INDEX acceptance_hash_key ON acceptance_index_rows(hash_key) USING HASH;");
      client.execute(
          "CREATE INDEX acceptance_btree_key ON acceptance_index_rows(tree_key) USING B_PLUS_TREE;");
      assertAffected(
          client, "INSERT INTO acceptance_index_rows VALUES (1001, 'hash-before', 9001);", 1);

      assertAffected(
          client, "UPDATE acceptance_index_rows SET hash_key = 'hash-after' WHERE id = 1001;", 1);
      assertAffected(
          client, "UPDATE acceptance_index_rows SET tree_key = 9002 WHERE id = 1001;", 1);
      assertNoRows(client, "SELECT id FROM acceptance_index_rows WHERE hash_key = 'hash-before';");
      assertEquals(
          1001,
          onlyInt(client, "SELECT id FROM acceptance_index_rows WHERE hash_key = 'hash-after';"));

      client.execute("BEGIN;");
      assertAffected(
          client,
          "UPDATE acceptance_index_rows SET hash_key = 'hash-rolled-back' WHERE id = 1001;",
          1);
      assertAffected(
          client, "UPDATE acceptance_index_rows SET tree_key = 9003 WHERE id = 1001;", 1);
      client.execute("ABORT;");
      assertEquals(
          1001,
          onlyInt(client, "SELECT id FROM acceptance_index_rows WHERE hash_key = 'hash-after';"));
      assertNoRows(
          client, "SELECT id FROM acceptance_index_rows WHERE hash_key = 'hash-rolled-back';");
      assertEquals(
          1001, onlyInt(client, "SELECT id FROM acceptance_index_rows WHERE tree_key = 9002;"));
      assertNoRows(client, "SELECT id FROM acceptance_index_rows WHERE tree_key = 9003;");
    }

    PlanExplanation hashPlan =
        PersistentPlanInspector.open(data)
            .explain("SELECT id FROM acceptance_index_rows WHERE hash_key = 'hash-after';");
    assertTrue(hashPlan.after().contains("IndexScan [index=acceptance_hash_key"), hashPlan.after());
    PlanExplanation btreePlan =
        PersistentPlanInspector.open(data)
            .explain("SELECT id FROM acceptance_index_rows WHERE tree_key = 9002;");
    assertTrue(
        btreePlan.after().contains("IndexScan [index=acceptance_btree_key"), btreePlan.after());
    assertFalse(
        hashPlan.after().contains("TableScan [table=acceptance_index_rows"), hashPlan.after());

    try (PackagedServerProcess restarted = startServer(data, ProtocolType.STRICT_2PL);
        AcceptanceClient client = connect(restarted)) {
      assertEquals(
          1001,
          onlyInt(client, "SELECT id FROM acceptance_index_rows WHERE hash_key = 'hash-after';"));
      assertEquals(
          1001, onlyInt(client, "SELECT id FROM acceptance_index_rows WHERE tree_key = 9002;"));
      assertNoRows(client, "SELECT id FROM acceptance_index_rows WHERE hash_key = 'hash-before';");
    }
  }
}
