package dynamitedb.qo.plan;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.AggregateExpression;
import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.CreateIndexStatement;
import dynamitedb.contracts.query.CreateTablePlan;
import dynamitedb.contracts.query.CreateTableStatement;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.DeleteStatement;
import dynamitedb.contracts.query.ExistsSubqueryExpression;
import dynamitedb.contracts.query.DropTablePlan;
import dynamitedb.contracts.query.DropTableStatement;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.FromSource;
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.InsertStatement;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.LimitPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.ScalarSubqueryExpression;
import dynamitedb.contracts.query.SelectItem;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TableSource;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.TransactionStatement;
import dynamitedb.contracts.query.UnaryExpression;
import dynamitedb.contracts.query.UpdateAssignment;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.contracts.query.UpdateStatement;
import dynamitedb.contracts.storage.Row;
import java.util.LinkedHashSet;
import java.util.List;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableDefinition;
import java.util.Optional;
import java.util.Set;

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
      if (update.predicate().isPresent()) source = filter(source, update.predicate().get());
      return new UpdatePlan(
          source,
          update.table(),
          update.assignments().stream()
              .map(a -> new UpdateAssignment(a.target(), withSubplans(a.expression())))
              .toList());
    }
    if (bound instanceof DeleteStatement delete) {
      PlanNode source = new TableScanPlan(delete.table(), delete.table());
      if (delete.predicate().isPresent()) source = filter(source, delete.predicate().get());
      return new DeletePlan(source, delete.table());
    }
    if (bound instanceof CreateIndexStatement create) {
      return new CreateIndexPlan(create.index(), create.table(), create.column(), create.kind());
    }
    if (bound instanceof CreateTableStatement create) {
      return new CreateTablePlan(
          new TableDefinition(
              create.table(),
              new Schema(create.columns()),
              create.primaryKeys().stream().findFirst(),
              create.foreignKeys()));
    }
    if (bound instanceof DropTableStatement drop) {
      return new DropTablePlan(drop.table(), drop.behavior());
    }
    if (bound instanceof TransactionStatement transaction) {
      return new TransactionCommandPlan(transaction.command());
    }
    throw invalid("Unsupported statement type: " + bound.getClass().getSimpleName());
  }

  private static PlanNode buildSelect(SelectStatement select) {
    PlanNode plan = buildFrom(select.from());
    if (select.predicate().isPresent()) plan = filter(plan, select.predicate().get());
    boolean aggregateQuery =
        !select.groupBy().isEmpty()
            || select.items().stream().anyMatch(item -> containsAggregate(item.expression()))
            || select.having().filter(PlanBuilder::containsAggregate).isPresent();
    if (aggregateQuery) {
      Set<AggregateExpression> aggregates = new LinkedHashSet<>();
      select.items().forEach(item -> collectAggregates(item.expression(), aggregates));
      select.having().ifPresent(expression -> collectAggregates(expression, aggregates));
      plan = new AggregatePlan(plan, select.groupBy(), List.copyOf(aggregates));
      if (select.having().isPresent()) plan = filter(plan, select.having().get());
    }
    boolean sortByExpressionAlias =
        select.orderBy().stream()
            .anyMatch(
                key ->
                    select.items().stream()
                        .anyMatch(
                            item ->
                                item.alias()
                                        .filter(key.column().column()::equalsIgnoreCase)
                                        .isPresent()
                                    && !(item.expression()
                                        instanceof dynamitedb.contracts.query.ColumnExpression)));
    if (!select.orderBy().isEmpty() && !sortByExpressionAlias) {
      plan = new SortPlan(plan, select.orderBy());
    }
    List<SelectItem> items =
        select.items().stream()
            .map(
                item ->
                    new dynamitedb.contracts.query.SelectItem(
                        withSubplans(item.expression()), item.alias()))
            .toList();
    plan = ProjectPlan.fromItems(plan, items);
    if (!select.orderBy().isEmpty() && sortByExpressionAlias) {
      plan = new SortPlan(plan, select.orderBy());
    }
    
    if (select.limit().isPresent()) {
      plan = new LimitPlan(plan, select.limit().get());
    }
    return plan;
  }

  private static boolean containsAggregate(Expression expression) {
    if (expression instanceof AggregateExpression) return true;
    if (expression instanceof BinaryExpression binary) {
      return containsAggregate(binary.left()) || containsAggregate(binary.right());
    }
    if (expression instanceof UnaryExpression unary) return containsAggregate(unary.operand());
    return false;
  }

  private static void collectAggregates(
      Expression expression, Set<AggregateExpression> aggregates) {
    if (expression instanceof AggregateExpression aggregate) {
      aggregates.add(aggregate);
    } else if (expression instanceof BinaryExpression binary) {
      collectAggregates(binary.left(), aggregates);
      collectAggregates(binary.right(), aggregates);
    } else if (expression instanceof UnaryExpression unary) {
      collectAggregates(unary.operand(), aggregates);
    }
  }

  private static PlanNode buildFrom(FromSource source) {
    if (source instanceof TableSource table) return new TableScanPlan(table.table(), table.alias());
    if (source instanceof JoinSource join) {
      return new JoinPlan(
          buildFrom(join.left()),
          buildFrom(join.right()),
          join.kind(),
          join.condition().map(PlanBuilder::withSubplans));
    }
    throw invalid("Unsupported FROM source: " + source.getClass().getSimpleName());
  }

  private static FilterPlan filter(PlanNode child, Expression predicate) {
    return new FilterPlan(child, withSubplans(predicate));
  }

  private static Expression withSubplans(Expression expression) {
    return switch (expression) {
      case BinaryExpression binary ->
          new BinaryExpression(
              withSubplans(binary.left()), binary.operator(), withSubplans(binary.right()));
      case UnaryExpression unary ->
          new UnaryExpression(unary.operator(), withSubplans(unary.operand()));
      case InSubqueryExpression in ->
          new InSubqueryExpression(
              in.operand(), in.query(), Optional.of(buildSelect(in.query())), in.negated());
      case ExistsSubqueryExpression exists ->
          new ExistsSubqueryExpression(
              exists.query(), Optional.of(buildSelect(exists.query())), exists.negated());
      case ScalarSubqueryExpression scalar ->
          new ScalarSubqueryExpression(scalar.query(), Optional.of(buildSelect(scalar.query())));
      default -> expression;
    };
  }

  private static DbmsException invalid(String message) {
    return new DbmsException(ErrorCode.INVALID_PLAN, message);
  }
}
