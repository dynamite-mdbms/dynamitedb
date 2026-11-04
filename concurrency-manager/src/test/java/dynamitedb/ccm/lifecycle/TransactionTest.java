package dynamitedb.ccm.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransactionTest {

  @Test
  void newTransactionStartsActiveWithItsIdAndTimestamp() {
    var tx = new Transaction(new TransactionId(3), 7);

    assertEquals(new TransactionId(3), tx.id());
    assertEquals(7, tx.timestamp());
    assertEquals(TransactionStatus.ACTIVE, tx.status());
    assertTrue(tx.isActive());
    assertFalse(tx.isFinal());
  }

  @Test
  void nonPositiveTimestampIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new Transaction(new TransactionId(1), 0));
  }

  @Test
  void statusTransitionsFollowTheLifecycleDiagram() {
    var allowed =
        Set.of(
            "ACTIVE PARTIALLY_COMMITTED",
            "ACTIVE FAILED",
            "PARTIALLY_COMMITTED COMMITTED",
            "PARTIALLY_COMMITTED FAILED",
            "FAILED ABORTED");
    for (var from : TransactionStatus.values()) {
      for (var to : TransactionStatus.values()) {
        assertEquals(
            allowed.contains(from + " " + to), Transaction.isAllowed(from, to), from + " " + to);
      }
    }
  }

  @Test
  void illegalTransitionKeepsCurrentStatus() {
    var tx = new Transaction(new TransactionId(1), 1);
    var error = assertThrows(DbmsException.class, () -> tx.transition(TransactionStatus.COMMITTED));
    assertEquals(ErrorCode.INVALID_STATE, error.getCode());
    assertEquals(TransactionStatus.ACTIVE, tx.status());

    tx.transition(TransactionStatus.FAILED);
    tx.transition(TransactionStatus.ABORTED);
    assertTrue(tx.isFinal());
    for (var target : TransactionStatus.values()) {
      assertThrows(DbmsException.class, () -> tx.transition(target));
    }
  }
}
