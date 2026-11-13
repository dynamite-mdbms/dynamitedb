package dynamitedb.ccm.timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.List;
import org.junit.jupiter.api.Test;

class SerializabilityCheckerTest {
  private final SerializabilityChecker checker = new SerializabilityChecker();

  @Test
  void readsNeverConflict() {
    var result =
        checker.verify(
            new Schedule(
                List.of(
                    op(1, 5, AccessMode.READ),
                    op(2, 5, AccessMode.READ),
                    op(3, 5, AccessMode.READ))));
    assertTrue(result.serializable());
    assertEquals(List.of(tx(1), tx(2), tx(3)), result.serialOrder());
  }

  @Test
  void writeWriteConflictCreatesEdge() {
    var result =
        checker.verify(
            new Schedule(List.of(op(1, 5, AccessMode.WRITE), op(2, 5, AccessMode.WRITE))));
    assertTrue(result.serializable());
    assertEquals(List.of(tx(1), tx(2)), result.serialOrder());
  }

  @Test
  void writeReadAndReadWriteConflictsCreateEdges() {
    var writeRead =
        checker.verify(
            new Schedule(List.of(op(1, 5, AccessMode.WRITE), op(2, 5, AccessMode.READ))));
    assertEquals(List.of(tx(1), tx(2)), writeRead.serialOrder());
    var readWrite =
        checker.verify(
            new Schedule(List.of(op(1, 5, AccessMode.READ), op(2, 5, AccessMode.WRITE))));
    assertEquals(List.of(tx(1), tx(2)), readWrite.serialOrder());
  }

  @Test
  void differentResourcesDoNotConflict() {
    var result =
        checker.verify(
            new Schedule(List.of(op(1, 5, AccessMode.WRITE), op(2, 6, AccessMode.WRITE))));
    assertTrue(result.serializable());
    assertEquals(List.of(tx(1), tx(2)), result.serialOrder());
  }

  @Test
  void cyclicConflictGraphIsNotSerializable() {
    var result =
        checker.verify(
            new Schedule(
                List.of(
                    op(1, 5, AccessMode.WRITE),
                    op(2, 5, AccessMode.WRITE),
                    op(2, 6, AccessMode.WRITE),
                    op(1, 6, AccessMode.READ))));
    assertFalse(result.serializable());
    assertTrue(result.serialOrder().isEmpty());
  }

  @Test
  void serialOrderBreaksTiesByAscendingTransactionId() {
    var result =
        checker.verify(
            new Schedule(
                List.of(
                    op(1, 5, AccessMode.WRITE),
                    op(2, 5, AccessMode.WRITE),
                    op(2, 6, AccessMode.WRITE),
                    op(3, 6, AccessMode.WRITE))));
    assertTrue(result.serializable());
    assertEquals(List.of(tx(1), tx(2), tx(3)), result.serialOrder());
  }

  private static ScheduledOperation op(long tx, long table, AccessMode mode) {
    return new ScheduledOperation(tx(tx), ResourceId.table(table), mode);
  }

  private static TransactionId tx(long id) {
    return new TransactionId(id);
  }
}
