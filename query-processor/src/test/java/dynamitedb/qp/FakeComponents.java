package dynamitedb.qp;

import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.IndexAdmin;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.storage.WriteReservationId;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tiruan SM dan CCM untuk test QP5; setiap pemanggilan dicatat ke events. */
public class FakeComponents
    implements WriteStore,
        ChangeLogger,
        IndexAdmin,
        TransactionManager,
        AccessController,
        CatalogAccess {

  public final List<String> events = new ArrayList<>();
  public final List<AccessRequest> requests = new ArrayList<>();
  public final List<PreparedWrite> applied = new ArrayList<>();
  public final Deque<AccessDecision> decisions = new ArrayDeque<>();
  public final Map<TransactionId, TransactionStatus> statuses = new HashMap<>();
  public RuntimeException logFailure;
  public RuntimeException commitFailure;
  private long nextTx = 10;
  private long nextLsn = 1;

  @Override
  public PreparedWrite prepareInsert(TransactionId tx, String table, Row row) {
    events.add("prepareInsert " + table);
    return prepared(tx, table, new RecordId(1, 1, 0, 0));
  }

  @Override
  public PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row) {
    events.add("prepareUpdate " + rid.slotNumber() + " " + row.values());
    return prepared(tx, "t", rid);
  }

  @Override
  public PreparedWrite prepareDelete(TransactionId tx, RecordId rid) {
    events.add("prepareDelete " + rid.slotNumber());
    return prepared(tx, "t", rid);
  }

  @Override
  public void applyWrite(PreparedWrite prepared, LogSequenceNumber lsn) {
    events.add("apply " + lsn.value());
    applied.add(prepared);
  }

  @Override
  public void cancelPrepared(PreparedWrite prepared) {
    events.add("cancel");
  }

  @Override
  public LogSequenceNumber recordChange(TransactionId tx, PreparedWrite write) {
    events.add("log");
    if (logFailure != null) {
      throw logFailure;
    }
    return new LogSequenceNumber(nextLsn++);
  }

  @Override
  public void createIndex(String name, String table, String column, IndexKind kind) {
    events.add("createIndex " + name + " " + table + "(" + column + ") " + kind);
  }

  @Override
  public TransactionId begin() {
    TransactionId id = new TransactionId(nextTx++);
    statuses.put(id, TransactionStatus.ACTIVE);
    events.add("begin " + id.value());
    return id;
  }

  @Override
  public void commit(TransactionId tx) {
    events.add("commit " + tx.value());
    if (commitFailure != null) {
      throw commitFailure;
    }
    statuses.put(tx, TransactionStatus.COMMITTED);
  }

  @Override
  public void abort(TransactionId tx) {
    events.add("abort " + tx.value());
    statuses.put(tx, TransactionStatus.ABORTED);
  }

  @Override
  public TransactionStatus status(TransactionId tx) {
    return statuses.getOrDefault(tx, TransactionStatus.ABORTED);
  }

  @Override
  public AccessDecision requestAccess(AccessRequest request) {
    requests.add(request);
    events.add("access " + request.mode());
    return decisions.isEmpty() ? AccessDecision.granted() : decisions.poll();
  }

  @Override
  public AccessDecision awaitAccess(WaitToken ticket) {
    events.add("await");
    return decisions.isEmpty() ? AccessDecision.granted() : decisions.poll();
  }

  @Override
  public List<String> listTables() {
    return List.of("t");
  }

  @Override
  public long getTableId(String table) {
    return 7;
  }

  @Override
  public Schema getSchema(String table) {
    return new Schema(List.of());
  }

  @Override
  public TableStatistics getStatistics(String table) {
    return new TableStatistics(0, 0, 0, Map.of());
  }

  @Override
  public List<IndexMetadata> listIndexes(String table) {
    return List.of();
  }

  private static PreparedWrite prepared(TransactionId tx, String table, RecordId rid) {
    return new PreparedWrite(
        tx,
        table,
        rid,
        RecordImage.absent(),
        RecordImage.absent(),
        new WriteReservationId(UUID.randomUUID()));
  }
}
