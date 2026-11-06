package dynamitedb.qp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortDirection;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.ComparisonOp;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.FakeComponents;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.operator.BlockNestedLoopJoinOperator;
import dynamitedb.qp.operator.FilterOperator;
import dynamitedb.qp.operator.IndexScanOperator;
import dynamitedb.qp.operator.Operator;
import dynamitedb.qp.operator.ProjectOperator;
import dynamitedb.qp.operator.ScanOperator;
import dynamitedb.qp.operator.SortOperator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlanTranslatorTest {

  private static final TransactionId TX = new TransactionId(5);
  private static final PlanNode SCAN = new TableScanPlan("employee", "e");
  private static final ColumnRef ID = new ColumnRef(Optional.of("e"), "id");

  private final FakeComponents fake = new FakeComponents();
  private final List<String> translated = new ArrayList<>();

  // nyatet node yang selesai diterjemahin, buat ngecek urutan child dulu baru induk
  private final PlanTranslator translator =
      new PlanTranslator(
          new ExpressionEvaluator(),
          new UnusedReader(),
          fake,
          new TransactionCoordinator(fake, fake, fake)) {
        @Override
        public Operator translate(PlanNode plan, TransactionId tx) {
          Operator operator = super.translate(plan, tx);
          translated.add(plan.getClass().getSimpleName());
          return operator;
        }
      };

  // tiap jenis plan harus jadi operator yang pasangannya
  @Test
  void mapsEveryRelationalNodeToItsOperator() {
    PlanNode indexScan =
        new IndexScanPlan(
            "employee_id",
            "employee",
            "e",
            new IndexPredicate(ComparisonOp.EQ, new IntValue(7)),
            Optional.empty());

    assertInstanceOf(ScanOperator.class, translate(SCAN));
    assertInstanceOf(IndexScanOperator.class, translate(indexScan));
    assertInstanceOf(FilterOperator.class, translate(new FilterPlan(SCAN, truth())));
    assertInstanceOf(ProjectOperator.class, translate(new ProjectPlan(SCAN, List.of(ID))));
    assertInstanceOf(
        BlockNestedLoopJoinOperator.class,
        translate(new JoinPlan(SCAN, SCAN, JoinKind.CARTESIAN, Optional.empty())));
    assertInstanceOf(
        SortOperator.class,
        translate(new SortPlan(SCAN, List.of(new SortKey(ID, SortDirection.ASC)))));
  }

  // Project(Filter(Scan)) harus selesai dari Scan dulu, terakhir Project
  @Test
  void translatesChildrenBeforeParent() {
    PlanNode plan = new ProjectPlan(new FilterPlan(SCAN, truth()), List.of(ID));

    translate(plan);

    assertEquals(List.of("TableScanPlan", "FilterPlan", "ProjectPlan"), translated);
  }

  // join harus nerjemahin kiri dan kanan sebelum join-nya sendiri
  @Test
  void translatesBothSidesOfJoin() {
    PlanNode right = new TableScanPlan("department", "d");

    translate(new JoinPlan(SCAN, right, JoinKind.THETA, Optional.of(truth())));

    assertEquals(List.of("TableScanPlan", "TableScanPlan", "JoinPlan"), translated);
  }

  // DML, CREATE INDEX, sama BEGIN ga boleh lewat translator
  @Test
  void rejectsNonRelationalPlans() {
    List<PlanNode> plans =
        List.of(
            new InsertPlan("employee", new Row(List.of())),
            new UpdatePlan(SCAN, "employee", ID, truth()),
            new DeletePlan(SCAN, "employee"),
            new CreateIndexPlan("employee_id", "employee", "id", IndexKind.HASH),
            new TransactionCommandPlan(TransactionCommand.BEGIN));

    for (PlanNode plan : plans) {
      DbmsException error = assertThrows(DbmsException.class, () -> translate(plan));
      assertEquals(ErrorCode.INVALID_PLAN, error.getCode());
    }
  }

  // helper biar ga nulis TX terus
  private Operator translate(PlanNode plan) {
    return translator.translate(plan, TX);
  }

  // kondisi TRUE sembarang, isinya ga penting buat test ini
  private static LiteralExpression truth() {
    return new LiteralExpression(new BooleanValue(true));
  }

  // operator cuma dibikin, ga dibuka, jadi reader-nya ga boleh kepanggil
  private static final class UnusedReader implements TupleReader {
    @Override
    public RowCursor openTableScan(String table, TransactionId tx) {
      throw new UnsupportedOperationException("openTableScan");
    }

    @Override
    public RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx) {
      throw new UnsupportedOperationException("openIndexScan");
    }

    @Override
    public Optional<StoredRow> fetch(RecordId rid, TransactionId tx) {
      throw new UnsupportedOperationException("fetch");
    }
  }
}
