package dynamitedb.ccm.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.api.TransactionWal;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.WriteReservationId;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionRecoveryBridgeTest {
  private final TransactionId tx = new TransactionId(7);
  private final List<String> events = new ArrayList<>();
  private final RecordingWal wal = new RecordingWal();
  private final TransactionRecoveryBridge bridge =
      new TransactionRecoveryBridge(wal, id -> events.add("rollback " + id));

  @ParameterizedTest
  @ValueSource(strings = {"insert", "update", "delete"})
  void forwardsOneUnmodifiedRecordPerPhysicalChange(String operation) {
    RecordImage existing = new RecordImage(true, ImmutableBytes.copyOf(new byte[] {0, 1, 2}));
    RecordImage before = operation.equals("insert") ? RecordImage.absent() : existing;
    RecordImage after =
        operation.equals("delete")
            ? RecordImage.absent()
            : new RecordImage(true, ImmutableBytes.copyOf(new byte[] {3, 4, 5}));
    var write =
        new PreparedWrite(
            tx,
            "students",
            new RecordId(3, 4, 5, 6),
            before,
            after,
            new WriteReservationId(UUID.randomUUID()));

    LogSequenceNumber result = bridge.recordChange(tx, write);

    assertSame(wal.lsn, result);
    assertEquals(List.of("change"), events);
    assertEquals(new ChangeRecord(tx, "students", write.rid(), before, after), wal.change);
    assertSame(before, wal.change.before());
    assertSame(after, wal.change.after());
  }

  @Test
  void forwardsTransactionEventsToTheirRespectiveFrmPorts() {
    bridge.begin(tx, 42);
    bridge.commit(tx);
    bridge.rollback(tx);

    assertEquals(List.of("begin T7 42", "commit T7", "rollback T7"), events);
  }

  @Test
  void rejectsAnotherTransactionsPreparedWriteWithoutLogging() {
    var write = write(new TransactionId(8));

    assertEquals(
        ErrorCode.INVALID_STATE,
        assertThrows(DbmsException.class, () -> bridge.recordChange(tx, write)).getCode());
    assertEquals(List.of(), events);
  }

  @Test
  void reportsAppendFailureWithoutRetryingTheChange() {
    wal.failure = new IllegalStateException("disk penuh");

    var error = assertThrows(DbmsException.class, () -> bridge.recordChange(tx, write(tx)));

    assertEquals(ErrorCode.WAL_FAILURE, error.getCode());
    assertSame(wal.failure, error.getCause());
    assertEquals(List.of("change"), events);
  }

  @Test
  void rejectsAnAbsentLsn() {
    wal.lsn = null;
    assertEquals(
        ErrorCode.WAL_FAILURE,
        assertThrows(DbmsException.class, () -> bridge.recordChange(tx, write(tx))).getCode());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void rejectsANonpositiveLsn(long value) {
    wal.lsn = new LogSequenceNumber(value);
    assertEquals(
        ErrorCode.WAL_FAILURE,
        assertThrows(DbmsException.class, () -> bridge.recordChange(tx, write(tx))).getCode());
  }

  private PreparedWrite write(TransactionId owner) {
    return new PreparedWrite(
        owner,
        "students",
        new RecordId(3, 4, 5, 6),
        RecordImage.absent(),
        new RecordImage(true, ImmutableBytes.copyOf(new byte[] {1})),
        new WriteReservationId(UUID.randomUUID()));
  }

  private final class RecordingWal implements TransactionWal {
    private LogSequenceNumber lsn = new LogSequenceNumber(23);
    private ChangeRecord change;
    private RuntimeException failure;

    @Override
    public void begin(TransactionId id, long timestamp) {
      events.add("begin " + id + " " + timestamp);
    }

    @Override
    public LogSequenceNumber appendChange(ChangeRecord value) {
      events.add("change");
      if (failure != null) {
        throw failure;
      }
      change = value;
      return lsn;
    }

    @Override
    public void commitDurably(TransactionId id) {
      events.add("commit " + id);
    }
  }
}
