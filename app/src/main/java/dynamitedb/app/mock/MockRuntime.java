package dynamitedb.app.mock;

import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.IndexAdmin;
import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanExplanation;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.WriteReservationId;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import dynamitedb.qp.QueryProcessorImpl;
import dynamitedb.qp.execution.DeleteExecutor;
import dynamitedb.qp.execution.ExecutionEngine;
import dynamitedb.qp.execution.PlanTranslator;
import dynamitedb.qp.execution.ReadExecutor;
import dynamitedb.qp.execution.TransactionCommandExecutor;
import dynamitedb.qp.execution.WriteCoordinator;
import dynamitedb.qp.execution.WriteExecutor;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.session.SessionManager;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Actual QP execution with finite SQL fixtures and in-memory doubles for external ports. This
 * fixture provides no parser, locking protocol, durable storage or recovery.
 */
public final class MockRuntime {
  private static final Schema NO_COLUMNS = new Schema(List.of());
  private final QueryProcessorImpl processor;

  public MockRuntime() {
    MockStorage storage = new MockStorage();
    TransactionCoordinator coordinator =
        new TransactionCoordinator(new MockTransactions(storage), new MockAccess(), storage);
    ExpressionEvaluator evaluator = new ExpressionEvaluator();
    PlanTranslator translator = new PlanTranslator(evaluator, storage, storage, coordinator);
    AtomicLong lsn = new AtomicLong();
    WriteCoordinator writes =
        new WriteCoordinator(storage, (tx, write) -> new LogSequenceNumber(lsn.incrementAndGet()));
    DeleteExecutor delete = new DeleteExecutor(storage, writes, translator, evaluator, coordinator);
    WriteExecutor writer =
        new WriteExecutor(
            storage, writes, translator, evaluator, coordinator, storage, delete, storage);
    ExecutionEngine engine =
        new ExecutionEngine(
            translator, new ReadExecutor(), writer, new TransactionCommandExecutor(coordinator));
    processor =
        new QueryProcessorImpl(new MockOptimizer(), new SessionManager(), engine, coordinator);
  }

  public QueryProcessorImpl processor() {
    return processor;
  }

  private static Schema schema(String table) {
    return new Schema(
        List.of(
            new Column(table, "id", DataType.INTEGER, 0, false),
            new Column(table, "name", DataType.VARCHAR, 128, true)));
  }

  private static FilterPlan byId(TableScanPlan scan, int value) {
    return new FilterPlan(
        scan,
        new BinaryExpression(
            new ColumnExpression(new ColumnRef(Optional.of(scan.alias()), "id")),
            BinaryOp.EQ,
            new LiteralExpression(new IntValue(value))));
  }

  private static final class MockOptimizer implements QueryOptimizer {
    @Override
    public PreparedQuery prepare(String sql) {
      String key =
          sql.strip().replaceFirst(";\\s*$", "").replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
      TableScanPlan scan = new TableScanPlan("demo", "demo");
      ColumnRef id = new ColumnRef(Optional.of("demo"), "id");
      PlanNode root =
          switch (key) {
            case "BEGIN", "BEGIN TRANSACTION" ->
                new TransactionCommandPlan(TransactionCommand.BEGIN);
            case "COMMIT" -> new TransactionCommandPlan(TransactionCommand.COMMIT);
            case "ABORT" -> new TransactionCommandPlan(TransactionCommand.ABORT);
            case "SELECT * FROM DEMO" -> scan;
            case "SELECT * FROM EMPTY" -> new TableScanPlan("empty", "empty");
            case "SELECT * FROM FAIL" -> new TableScanPlan("fail", "fail");
            case "SELECT ID FROM DEMO" -> new ProjectPlan(scan, List.of(id));
            case "SELECT * FROM DEMO WHERE ID = 1" -> byId(scan, 1);
            case "SELECT * FROM DEMO WHERE ID = 4" -> byId(scan, 4);
            case "SELECT * FROM DEMO ORDER BY ID DESC" ->
                new SortPlan(scan, List.of(new SortKey(id, SortDirection.DESC)));
            case "SELECT * FROM DEMO, EMPTY" ->
                new JoinPlan(
                    scan,
                    new TableScanPlan("empty", "empty"),
                    JoinKind.CARTESIAN,
                    Optional.empty());
            case "SELECT * FROM DEMO, DEMO" ->
                new JoinPlan(
                    scan, new TableScanPlan("demo", "other"), JoinKind.CARTESIAN, Optional.empty());
            case "INSERT INTO DEMO VALUES (4, 'DINA')" ->
                new InsertPlan("demo", new Row(List.of(new IntValue(4), new TextValue("Dina"))));
            case "UPDATE DEMO SET NAME = 'DINA'" ->
                update(scan, new ColumnRef(Optional.of("demo"), "name"));
            case "UPDATE DEMO SET NAME = 'DINA' WHERE ID = 1" ->
                update(byId(scan, 1), new ColumnRef(Optional.of("demo"), "name"));
            case "DELETE FROM DEMO WHERE ID = 1" -> new DeletePlan(byId(scan, 1), "demo");
            case "DELETE FROM DEMO WHERE ID = 4" -> new DeletePlan(byId(scan, 4), "demo");
            case "CREATE INDEX IDX_DEMO_ID ON DEMO(ID)" ->
                new CreateIndexPlan("idx_demo_id", "demo", "id", IndexKind.HASH);
            default ->
                throw new DbmsException(
                    ErrorCode.SQL_SYNTAX,
                    "Unknown fixture SQL. Use a command from the QP driver suite.");
          };
      Schema output =
          switch (root) {
            case TableScanPlan table -> schema(table.table());
            case ProjectPlan ignored -> new Schema(List.of(schema("demo").columns().getFirst()));
            case FilterPlan ignored -> schema("demo");
            case SortPlan ignored -> schema("demo");
            case JoinPlan join -> {
              String right = ((TableScanPlan) join.right()).alias();
              yield new Schema(
                  java.util.stream.Stream.concat(
                          schema("demo").columns().stream(), schema(right).columns().stream())
                      .toList());
            }
            default -> NO_COLUMNS;
          };
      return new PreparedQuery(root, output);
    }

    private UpdatePlan update(PlanNode source, ColumnRef column) {
      return new UpdatePlan(source, "demo", column, new LiteralExpression(new TextValue("Dina")));
    }

    @Override
    public double estimateCost(PlanNode plan) {
      return 0;
    }

    @Override
    public PlanExplanation explain(String sql) {
      String plan = prepare(sql).root().toString();
      return new PlanExplanation(plan, plan, 0);
    }
  }

  /** Rows and write reservations are test data; there are no physical pages or indexes. */
  private static final class MockStorage
      implements CatalogAccess, TupleReader, WriteStore, IndexAdmin {
    private final Map<RecordId, Row> committed = new LinkedHashMap<>();
    private final Map<TransactionId, Map<RecordId, Optional<Row>>> changes = new HashMap<>();
    private final Map<WriteReservationId, Mutation> reservations = new HashMap<>();
    private final Map<String, IndexMetadata> indexes = new LinkedHashMap<>();
    private int nextSlot;

    private MockStorage() {
      for (Row row :
          List.of(
              new Row(List.of(new IntValue(1), new TextValue("Ayu"))),
              new Row(List.of(new IntValue(2), new TextValue("Ishak 東京 😀"))),
              new Row(List.of(new IntValue(3), NullValue.INSTANCE)))) {
        committed.put(new RecordId(1, 0, nextSlot++, 0), row);
      }
    }

    @Override
    public RowCursor openTableScan(String table, TransactionId tx) {
      if (table.equals("fail")) {
        throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Simulated storage failure");
      }
      long tableId = getTableId(table);
      var rows =
          visible(tx).entrySet().stream()
              .filter(entry -> entry.getKey().tableId() == tableId)
              .map(entry -> new StoredRow(entry.getKey(), entry.getValue()))
              .toList()
              .iterator();
      return new RowCursor() {
        @Override
        public Optional<StoredRow> next() {
          return rows.hasNext() ? Optional.of(rows.next()) : Optional.empty();
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx) {
      throw new DbmsException(
          ErrorCode.UNSUPPORTED_OPERATION, "No physical index in the QP fixture");
    }

    @Override
    public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
      return Optional.ofNullable(visible(tx).get(rid)).map(row -> new StoredRow(rid, row));
    }

    @Override
    public synchronized PreparedWrite prepareInsert(TransactionId tx, String table, Row row) {
      return prepare(
          tx, table, new RecordId(getTableId(table), 0, nextSlot++, 0), Optional.of(row));
    }

    @Override
    public synchronized PreparedWrite prepareUpdate(TransactionId tx, RecordId rid, Row row) {
      return prepare(tx, "demo", rid, Optional.of(row));
    }

    @Override
    public synchronized PreparedWrite prepareDelete(TransactionId tx, RecordId rid) {
      return prepare(tx, "demo", rid, Optional.empty());
    }

    private PreparedWrite prepare(TransactionId tx, String table, RecordId rid, Optional<Row> row) {
      // Dummy images: the fixture does not encode rows or implement WAL.
      RecordImage before = image(visible(tx).containsKey(rid));
      PreparedWrite prepared =
          new PreparedWrite(
              tx,
              table,
              rid,
              before,
              image(row.isPresent()),
              new WriteReservationId(UUID.randomUUID()));
      reservations.put(prepared.reservation(), new Mutation(prepared, row));
      return prepared;
    }

    private static RecordImage image(boolean exists) {
      return new RecordImage(exists, ImmutableBytes.copyOf(new byte[0]));
    }

    @Override
    public synchronized void applyWrite(PreparedWrite prepared, LogSequenceNumber lsn) {
      Mutation mutation = reservations.remove(prepared.reservation());
      if (mutation == null || !mutation.prepared().equals(prepared)) {
        throw new DbmsException(ErrorCode.INVALID_STATE, "Unknown fixture write reservation");
      }
      changes
          .computeIfAbsent(prepared.tx(), ignored -> new LinkedHashMap<>())
          .put(prepared.rid(), mutation.row());
    }

    @Override
    public synchronized void cancelPrepared(PreparedWrite prepared) {
      reservations.remove(prepared.reservation());
    }

    private synchronized Map<RecordId, Row> visible(TransactionId tx) {
      Map<RecordId, Row> rows = new LinkedHashMap<>(committed);
      changes
          .getOrDefault(tx, Map.of())
          .forEach(
              (rid, row) -> {
                if (row.isPresent()) rows.put(rid, row.get());
                else rows.remove(rid);
              });
      return rows;
    }

    private synchronized void finish(TransactionId tx, boolean commit) {
      if (commit) {
        changes
            .getOrDefault(tx, Map.of())
            .forEach(
                (rid, row) -> {
                  if (row.isPresent()) committed.put(rid, row.get());
                  else committed.remove(rid);
                });
      }
      changes.remove(tx);
      reservations.values().removeIf(mutation -> mutation.prepared().tx().equals(tx));
    }

    @Override
    public synchronized void createIndex(String name, String table, String column, IndexKind kind) {
      indexes.put(name, new IndexMetadata(name, table, column, kind));
    }

    @Override
    public List<String> listTables() {
      return List.of("demo", "empty", "fail");
    }

    @Override
    public long getTableId(String table) {
      int index = listTables().indexOf(table);
      if (index < 0) throw new DbmsException(ErrorCode.SQL_BIND, "Unknown fixture table: " + table);
      return index + 1L;
    }

    @Override
    public Schema getSchema(String table) {
      getTableId(table);
      return schema(table);
    }

    @Override
    public synchronized TableStatistics getStatistics(String table) {
      return new TableStatistics(table.equals("demo") ? committed.size() : 0, 1, 32, Map.of());
    }

    @Override
    public synchronized List<IndexMetadata> listIndexes(String table) {
      return indexes.values().stream().filter(index -> index.table().equals(table)).toList();
    }

    private record Mutation(PreparedWrite prepared, Optional<Row> row) {}
  }

  private static final class MockTransactions implements TransactionManager {
    private final AtomicLong sequence = new AtomicLong();
    private final Map<TransactionId, TransactionStatus> states = new ConcurrentHashMap<>();
    private final MockStorage storage;

    private MockTransactions(MockStorage storage) {
      this.storage = storage;
    }

    @Override
    public TransactionId begin() {
      TransactionId id = new TransactionId(sequence.incrementAndGet());
      states.put(id, TransactionStatus.ACTIVE);
      return id;
    }

    @Override
    public void commit(TransactionId tx) {
      requireActive(tx);
      storage.finish(tx, true);
      states.put(tx, TransactionStatus.COMMITTED);
    }

    @Override
    public void abort(TransactionId tx) {
      requireActive(tx);
      storage.finish(tx, false);
      states.put(tx, TransactionStatus.ABORTED);
    }

    @Override
    public TransactionStatus status(TransactionId tx) {
      return states.get(tx);
    }

    private void requireActive(TransactionId tx) {
      if (status(tx) != TransactionStatus.ACTIVE)
        throw new DbmsException(ErrorCode.INVALID_STATE, "Transaction is not active");
    }
  }

  private static final class MockAccess implements AccessController {
    @Override
    public AccessDecision requestAccess(AccessRequest request) {
      return AccessDecision.granted();
    }

    @Override
    public AccessDecision awaitAccess(WaitToken token) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "The fixture never waits for access");
    }
  }
}
