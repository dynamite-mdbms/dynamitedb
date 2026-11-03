package dynamitedb.app.cli;

import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** Full result rendering, retaining headers for empty SELECTs and every returned row. */
public final class ResultRenderer {
  public void print(QueryResult result, PrintWriter out) {
    if (result.schema().columns().isEmpty()) {
      String message = result.message() == null ? "" : result.message();
      if (!message.isBlank()) out.println(message);
      if (!List.of("BEGIN", "COMMIT", "ABORT").contains(message)) {
        out.println(result.affectedRows() + " row(s) affected");
      }
      out.flush();
      return;
    }
    List<String> header = result.schema().columns().stream().map(column -> column.name()).toList();
    List<List<String>> rows = new ArrayList<>();
    rows.add(header);
    result
        .rows()
        .forEach(row -> rows.add(row.values().stream().map(ResultRenderer::format).toList()));
    int[] widths = new int[header.size()];
    for (List<String> row : rows) {
      for (int column = 0; column < widths.length; column++) {
        widths[column] = Math.max(widths[column], width(row.get(column)));
      }
    }
    StringBuilder border = new StringBuilder("+");
    for (int width : widths) border.append("-".repeat(width + 2)).append('+');
    out.println(border);
    printRow(header, widths, out);
    out.println(border);
    for (int index = 1; index < rows.size(); index++) printRow(rows.get(index), widths, out);
    out.println(border);
    out.println(result.rows().size() + " row(s)");
    out.flush();
  }

  private static void printRow(List<String> cells, int[] widths, PrintWriter out) {
    out.print('|');
    for (int column = 0; column < widths.length; column++) {
      String cell = cells.get(column);
      out.print(" " + cell + " ".repeat(widths[column] - width(cell) + 1) + "|");
    }
    out.println();
  }

  private static int width(String value) {
    return value.codePointCount(0, value.length());
  }

  private static String format(Value value) {
    return switch (value) {
      case NullValue ignored -> "NULL";
      case IntValue integer -> Integer.toString(integer.value());
      case FloatValue number -> Double.toString(number.value());
      case BooleanValue bool -> Boolean.toString(bool.value());
      case TextValue text ->
          text.value().replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    };
  }
}
