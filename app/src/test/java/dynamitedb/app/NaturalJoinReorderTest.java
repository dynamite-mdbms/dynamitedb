package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.AccessController;
import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.contracts.transaction.WaitToken;
import dynamitedb.qo.binding.Binder;
import dynamitedb.qo.join.AccessPathOptimizer;
import dynamitedb.qo.join.JoinOrderOptimizer;
import dynamitedb.qo.join.JoinRewriter;
import dynamitedb.qo.parser.ExpressionParser;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.parser.StatementParser;
import dynamitedb.qo.plan.PlanBuilder;
import dynamitedb.qo.pushdown.ProjectionPushdown;
import dynamitedb.qo.pushdown.SelectionPushdown;
import dynamitedb.qp.execution.PlanTranslator;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.Operator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every NATURAL JOIN order QO proposes returns the initial plan's rows when QP executes it. */
class NaturalJoinReorderTest {

  private static final TransactionId TX = new TransactionId(1);
  private final MemoryDatabase db = new MemoryDatabase();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT * FROM student NATURAL JOIN enrollment",
        "SELECT enrollment.course_id, student.name, student.student_id"
            + " FROM student NATURAL JOIN enrollment"
            + " WHERE student.student_id < enrollment.course_id ORDER BY student.student_id",
        "SELECT course.title, student.name, student.student_id, enrollment.course_id"
            + " FROM student NATURAL JOIN enrollment NATURAL JOIN course",
        "SELECT * FROM enrollment NATURAL JOIN student NATURAL JOIN course"
      })
  void everyCandidateMatchesInitialPlan(String sql) {
    Binder binder = new Binder(db);
    Statement bound =
        binder.bind(
            new SqlParser(new StatementParser(), new ExpressionParser())
                .parse(new Lexer().tokenize(sql)));
    PlanNode initial = new PlanBuilder().build(bound);
    PlanNode rewritten = initial;
    for (var rewrite :
        List.of(
            new SelectionPushdown(),
            new ProjectionPushdown(db),
            new JoinRewriter(),
            new AccessPathOptimizer(db))) {
      rewritten = rewrite.rewrite(rewritten);
    }

    List<PlanNode> candidates = new JoinOrderOptimizer(db).enumerate(rewritten);

    assertTrue(candidates.size() > 1, "a swapped order must be proposed");
    List<String> expected = execute(initial);
    assertTrue(!expected.isEmpty());
    for (PlanNode candidate : candidates) {
      assertEquals(expected, execute(candidate), candidate.toString());
    }
    int width = binder.outputSchema(bound).columns().size();
    assertTrue(expected.stream().allMatch(row -> row.split("\\|", -1).length == width));
  }

  /** Rows as sorted strings, so different join orders compare as multisets. */
  private List<String> execute(PlanNode plan) {
    var coordinator = new TransactionCoordinator(db, db, db);
    Operator root =
        new PlanTranslator(new ExpressionEvaluator(), db, db, coordinator).translate(plan, TX);
    List<String> rows = new ArrayList<>();
    root.open();
    try {
      for (var row = root.next(); row.isPresent(); row = root.next()) {
        rows.add(String.join("|", row.get().row().values().stream().map(Value::toString).toList()));
      }
    } finally {
      root.close();
    }
    return rows.stream().sorted().toList();
  }

  /** In-memory catalog and heap with every access granted. */
  private static final class MemoryDatabase
      implements CatalogAccess, TupleReader, AccessController, TransactionManager {

    private final Map<String, Schema> schemas =
        Map.of(
            "student",
            schema("student", column("student_id", DataType.INTEGER), varchar("name")),
            "enrollment",
            schema(
                "enrollment",
                column("student_id", DataType.INTEGER),
                column("course_id", DataType.INTEGER),
                column("score", DataType.FLOAT)),
            "course",
            schema("course", column("course_id", DataType.INTEGER), varchar("title")));

    private final Map<String, List<Row>> rows =
        Map.of(
            "student",
            List.of(row(1, "Ana"), row(2, "Budi"), row(3, "Cici")),
            "enrollment",
            List.of(
                row(1, 10, 80.0),
                row(1, 20, 70.0),
                row(2, 10, 90.0),
                row(2, 1, 50.0),
                row(4, 20, 60.0)),
            "course",
            List.of(row(10, "DB"), row(20, "OS"), row(30, "AI")));

    @Override
    public List<String> listTables() {
      return List.of("course", "enrollment", "student");
    }

    @Override
    public long getTableId(String table) {
      return listTables().indexOf(table) + 1L;
    }

    @Override
    public Schema getSchema(String table) {
      return schemas.get(table);
    }

    @Override
    public TableStatistics getStatistics(String table) {
      return new TableStatistics(rows.get(table).size(), 1, 16, Map.of());
    }

    @Override
    public List<IndexMetadata> listIndexes(String table) {
      return List.of();
    }

    @Override
    public RowCursor openTableScan(String table, TransactionId tx) {
      Iterator<Row> source = rows.get(table).iterator();
      return new RowCursor() {
        private int slot;

        @Override
        public Optional<StoredRow> next() {
          if (!source.hasNext()) return Optional.empty();
          return Optional.of(
              new StoredRow(new RecordId(getTableId(table), 0, slot++, 0), source.next()));
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx) {
      throw new UnsupportedOperationException("no indexes");
    }

    @Override
    public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
      throw new UnsupportedOperationException("read-only scans");
    }

    @Override
    public AccessDecision requestAccess(AccessRequest request) {
      return AccessDecision.granted();
    }

    @Override
    public AccessDecision awaitAccess(WaitToken ticket) {
      throw new UnsupportedOperationException("never waits");
    }

    @Override
    public TransactionId begin() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void commit(TransactionId tx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void abort(TransactionId tx) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TransactionStatus status(TransactionId tx) {
      throw new UnsupportedOperationException();
    }

    private static Schema schema(String table, Column... columns) {
      return new Schema(
          List.of(columns).stream()
              .map(c -> new Column(table, c.name(), c.dataType(), c.maxLength(), c.nullable()))
              .toList());
    }

    private static Column column(String name, DataType type) {
      return new Column(null, name, type, 0, false);
    }

    private static Column varchar(String name) {
      return new Column(null, name, DataType.VARCHAR, 20, false);
    }

    private static Row row(Object... values) {
      return new Row(
          List.of(values).stream()
              .map(
                  v ->
                      switch (v) {
                        case Integer i -> (Value) new IntValue(i);
                        case Double d -> new FloatValue(d);
                        default -> new TextValue((String) v);
                      })
              .toList());
    }
  }
}
