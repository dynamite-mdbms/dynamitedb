package dynamitedb.qo.plan;

import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import java.util.stream.Collectors;

/** Menampilkan plan dalam bentuk pohon teks (QO-15). */
public class PlanVisualizer {

  public String render(PlanNode plan) {
    StringBuilder output = new StringBuilder();
    renderNode(plan, 0, output);
    return output.toString().stripTrailing();
  }

  private static void renderNode(PlanNode plan, int depth, StringBuilder output) {
    output.append("  ".repeat(depth)).append(label(plan)).append(System.lineSeparator());
    if (plan instanceof FilterPlan filter) renderNode(filter.child(), depth + 1, output);
    else if (plan instanceof ProjectPlan project) renderNode(project.child(), depth + 1, output);
    else if (plan instanceof SortPlan sort) renderNode(sort.child(), depth + 1, output);
    else if (plan instanceof JoinPlan join) {
      renderNode(join.left(), depth + 1, output);
      renderNode(join.right(), depth + 1, output);
    } else if (plan instanceof UpdatePlan update) renderNode(update.source(), depth + 1, output);
    else if (plan instanceof DeletePlan delete) renderNode(delete.source(), depth + 1, output);
  }

  private static String label(PlanNode plan) {
    if (plan instanceof TableScanPlan scan) {
      return "TableScan [table=" + scan.table() + ", alias=" + scan.alias() + "]";
    }
    if (plan instanceof IndexScanPlan scan) {
      return "IndexScan [index="
          + scan.index()
          + ", table="
          + scan.table()
          + ", predicate="
          + scan.predicate()
          + ", residual="
          + scan.residual().map(PlanVisualizer::expression).orElse("none")
          + "]";
    }
    if (plan instanceof FilterPlan filter) {
      return "Filter [predicate=" + expression(filter.predicate()) + "]";
    }
    if (plan instanceof ProjectPlan project) {
      return "Project [columns=" + columns(project.columns()) + "]";
    }
    if (plan instanceof SortPlan sort) {
      String keys =
          sort.keys().stream()
              .map(key -> column(key.column()) + " " + key.direction())
              .collect(Collectors.joining(", "));
      return "Sort [keys=" + keys + "]";
    }
    if (plan instanceof JoinPlan join) {
      return "Join [kind="
          + join.kind()
          + ", condition="
          + join.condition().map(PlanVisualizer::expression).orElse("none")
          + "]";
    }
    if (plan instanceof InsertPlan insert) {
      return "Insert [table=" + insert.table() + ", values=" + insert.row().values() + "]";
    }
    if (plan instanceof UpdatePlan update) {
      return "Update [table="
          + update.table()
          + ", target="
          + column(update.target())
          + ", value="
          + expression(update.value())
          + "]";
    }
    if (plan instanceof DeletePlan delete) return "Delete [table=" + delete.table() + "]";
    if (plan instanceof CreateIndexPlan create) {
      return "CreateIndex [index="
          + create.index()
          + ", table="
          + create.table()
          + ", column="
          + create.column()
          + ", kind="
          + create.kind()
          + "]";
    }
    if (plan instanceof TransactionCommandPlan command) {
      return "Transaction [command=" + command.command() + "]";
    }
    return plan.getClass().getSimpleName();
  }

  private static String expression(Expression expression) {
    if (expression instanceof ColumnExpression column) return column(column.column());
    if (expression instanceof LiteralExpression literal) return literal.value().toString();
    if (expression instanceof BinaryExpression binary) {
      return "("
          + expression(binary.left())
          + " "
          + binary.operator()
          + " "
          + expression(binary.right())
          + ")";
    }
    return expression.toString();
  }

  private static String columns(java.util.List<ColumnRef> columns) {
    return columns.stream().map(PlanVisualizer::column).collect(Collectors.joining(", "));
  }

  private static String column(ColumnRef column) {
    return column.relation().map(relation -> relation + ".").orElse("") + column.column();
  }
}
