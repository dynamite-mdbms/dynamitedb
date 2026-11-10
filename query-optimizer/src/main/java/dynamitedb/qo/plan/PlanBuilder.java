package dynamitedb.qo.plan;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.CreateIndexStatement;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.DeleteStatement;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.FromSource;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.InsertStatement;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TableSource;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.TransactionStatement;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.query.UpdateStatement;
import dynamitedb.contracts.storage.Row;

/** Mengubah statement hasil binding menjadi PlanNode (QO-02, QO-03). */
public class PlanBuilder {

  public PlanNode build(Statement bound) {
    if (bound instanceof SelectStatement select) return buildSelect(select);
    if (bound instanceof InsertStatement insert) {
      var values =
          insert.values().stream()
              .map(
                  expression -> {
                    if (expression instanceof LiteralExpression literal) return literal.value();
                    throw invalid("INSERT values must be bound to literals");
                  })
              .toList();
      return new InsertPlan(insert.table(), new Row(values));
    }
    if (bound instanceof UpdateStatement update) {
      PlanNode source = new TableScanPlan(update.table(), update.table());
      if (update.predicate().isPresent()) source = new FilterPlan(source, update.predicate().get());
      return new UpdatePlan(source, update.table(), update.target(), update.expression());
    }
    if (bound instanceof DeleteStatement delete) {
      PlanNode source = new TableScanPlan(delete.table(), delete.table());
      if (delete.predicate().isPresent()) source = new FilterPlan(source, delete.predicate().get());
      return new DeletePlan(source, delete.table());
    }
    if (bound instanceof CreateIndexStatement create) {
      return new CreateIndexPlan(create.index(), create.table(), create.column(), create.kind());
    }
    if (bound instanceof TransactionStatement transaction) {
      return new TransactionCommandPlan(transaction.command());
    }
    throw invalid("Unsupported statement type: " + bound.getClass().getSimpleName());
  }

  private static PlanNode buildSelect(SelectStatement select) {
    PlanNode plan = buildFrom(select.from());
    if (select.predicate().isPresent()) plan = new FilterPlan(plan, select.predicate().get());
    if (!select.orderBy().isEmpty()) plan = new SortPlan(plan, select.orderBy());
    return new ProjectPlan(plan, select.projection());
  }

  private static PlanNode buildFrom(FromSource source) {
    if (source instanceof TableSource table) return new TableScanPlan(table.table(), table.alias());
    if (source instanceof JoinSource join) {
      return new JoinPlan(
          buildFrom(join.left()), buildFrom(join.right()), join.kind(), join.condition());
    }
    throw invalid("Unsupported FROM source: " + source.getClass().getSimpleName());
  }

  private static DbmsException invalid(String message) {
    return new DbmsException(ErrorCode.INVALID_PLAN, message);
  }
}
