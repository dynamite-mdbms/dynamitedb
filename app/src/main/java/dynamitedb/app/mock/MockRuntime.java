package dynamitedb.app.mock;

import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.api.TransactionManager;
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
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.AccessState;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import dynamitedb.qp.QueryProcessorImpl;
import dynamitedb.qp.execution.TransactionCommandExecutor;
import dynamitedb.qp.session.SessionContext;
import dynamitedb.qp.session.SessionManager;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed test doubles for QP1. This is not a SQL parser, storage engine, or CCM implementation. */
public final class MockRuntime {
  private static final Schema DEMO_SCHEMA =
      new Schema(
          List.of(
              new Column("demo", "id", DataType.INTEGER, 0, false),
              new Column("demo", "name", DataType.VARCHAR, 128, true)));
  private static final List<Row> DEMO_ROWS =
      List.of(
          new Row(List.of(new IntValue(1), new TextValue("Ayu"))),
          new Row(List.of(new IntValue(2), new TextValue("Ishak 東京 😀"))),
          new Row(List.of(new IntValue(3), NullValue.INSTANCE)));
  private static final Schema NO_COLUMNS = new Schema(List.of());
  private final SessionManager sessions = new SessionManager();
  private final TransactionCoordinator coordinator = new MockCoordinator(new MockTransactions());
  private final QueryOptimizer optimizer = new MockOptimizer();

  public QueryProcessorImpl processor() {
    return new QueryProcessorImpl(optimizer, sessions, this::executePrepared, coordinator);
  }

  private QueryResult executePrepared(PreparedQuery prepared, SessionContext session) {
    PlanNode plan = prepared.root();
    if (plan instanceof TransactionCommandPlan command) {
      return new TransactionCommandExecutor(coordinator).execute(command.command(), session);
    }
    if (session.activeTx().isEmpty()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Mock execution requires a transaction");
    }
    if (plan instanceof TableScanPlan scan) {
      if (scan.table().equals("fail")) {
        throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Simulated storage failure");
      }
      return new QueryResult(
          prepared.outputSchema(),
          scan.table().equals("empty") ? List.of() : DEMO_ROWS,
          0,
          "SELECT");
    }
    if (plan instanceof FilterPlan) {
      return new QueryResult(prepared.outputSchema(), List.of(DEMO_ROWS.getFirst()), 0, "SELECT");
    }
    if (plan instanceof SortPlan) {
      return new QueryResult(prepared.outputSchema(), DEMO_ROWS.reversed(), 0, "SELECT");
    }
    if (plan instanceof JoinPlan) {
      return new QueryResult(prepared.outputSchema(), List.of(), 0, "SELECT");
    }
    String message = plan instanceof CreateIndexPlan ? "CREATE INDEX" : "MOCK WRITE";
    return new QueryResult(NO_COLUMNS, List.of(), plan instanceof CreateIndexPlan ? 0 : 1, message);
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
            case "SELECT * FROM DEMO WHERE ID = 1" ->
                new FilterPlan(
                    scan,
                    new BinaryExpression(
                        new ColumnExpression(id),
                        BinaryOp.EQ,
                        new LiteralExpression(new IntValue(1))));
            case "SELECT * FROM DEMO ORDER BY ID DESC" ->
                new SortPlan(scan, List.of(new SortKey(id, SortDirection.DESC)));
            case "SELECT * FROM DEMO, EMPTY" ->
                new JoinPlan(
                    scan,
                    new TableScanPlan("empty", "empty"),
                    JoinKind.CARTESIAN,
                    Optional.empty());
            case "INSERT INTO DEMO VALUES (4, 'DINA')" ->
                new InsertPlan("demo", new Row(List.of(new IntValue(4), new TextValue("Dina"))));
            case "UPDATE DEMO SET NAME = 'DINA'" ->
                new UpdatePlan(
                    scan,
                    "demo",
                    new ColumnRef(Optional.of("demo"), "name"),
                    new LiteralExpression(new TextValue("Dina")));
            case "DELETE FROM DEMO WHERE ID = 1" -> new DeletePlan(scan, "demo");
            case "CREATE INDEX IDX_DEMO_ID ON DEMO(ID)" ->
                new CreateIndexPlan("idx_demo_id", "demo", "id", IndexKind.HASH);
            default ->
                throw new DbmsException(
                    ErrorCode.SQL_SYNTAX, "Unknown mock SQL. Use a documented demo command.");
          };
      boolean read =
          root instanceof TableScanPlan
              || root instanceof FilterPlan
              || root instanceof SortPlan
              || root instanceof JoinPlan;
      return new PreparedQuery(root, read ? DEMO_SCHEMA : NO_COLUMNS);
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

  private static final class MockTransactions implements TransactionManager {
    private final AtomicLong sequence = new AtomicLong();
    private final Map<TransactionId, TransactionStatus> states = new ConcurrentHashMap<>();

    @Override
    public TransactionId begin() {
      TransactionId id = new TransactionId(sequence.incrementAndGet());
      states.put(id, TransactionStatus.ACTIVE);
      return id;
    }

    @Override
    public void commit(TransactionId tx) {
      requireActive(tx);
      states.put(tx, TransactionStatus.COMMITTED);
    }

    @Override
    public void abort(TransactionId tx) {
      requireActive(tx);
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

  private static final class MockCoordinator extends TransactionCoordinator {
    private final TransactionManager transactions;

    private MockCoordinator(TransactionManager transactions) {
      super(transactions, new MockAccess(), new MockCatalog());
      this.transactions = transactions;
    }

    @Override
    public TransactionId begin(SessionContext session) {
      if (session.activeTx().isPresent())
        throw new DbmsException(ErrorCode.INVALID_STATE, "Transaction is already active");
      TransactionId id = transactions.begin();
      session.setActiveTx(Optional.of(id));
      return id;
    }

    @Override
    public void commit(SessionContext session) {
      transactions.commit(active(session));
      session.setActiveTx(Optional.empty());
    }

    @Override
    public void abort(SessionContext session) {
      transactions.abort(active(session));
      session.setActiveTx(Optional.empty());
    }

    private TransactionId active(SessionContext session) {
      return session
          .activeTx()
          .orElseThrow(() -> new DbmsException(ErrorCode.INVALID_STATE, "No active transaction"));
    }
  }

  private static final class MockAccess implements AccessController {
    @Override
    public AccessDecision requestAccess(AccessRequest request) {
      return new AccessDecision(AccessState.GRANTED, Optional.empty(), "mock grant");
    }

    @Override
    public AccessDecision awaitAccess(WaitToken token) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Mock access does not issue wait tickets");
    }
  }

  private static final class MockCatalog implements CatalogAccess {
    @Override
    public List<String> listTables() {
      return List.of("demo", "empty");
    }

    @Override
    public long getTableId(String table) {
      return table.equals("demo") ? 1 : 2;
    }

    @Override
    public Schema getSchema(String table) {
      return DEMO_SCHEMA;
    }

    @Override
    public TableStatistics getStatistics(String table) {
      return new TableStatistics(3, 1, 32, Map.of("id", 3L));
    }

    @Override
    public List<IndexMetadata> listIndexes(String table) {
      return List.of();
    }
  }
}
