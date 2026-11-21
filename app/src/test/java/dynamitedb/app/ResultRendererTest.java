package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.app.cli.ResultRenderer;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultRendererTest {
  @Test
  void duplicateJoinHeadersUseTheirRelationOrAlias() {
    var result =
        new QueryResult(
            schema("student", "student_id", "enrollment", "student_id"),
            List.of(new Row(List.of(new IntValue(1), new IntValue(1)))),
            0,
            "");
    String output = render(result);
    assertEquals(List.of("student.student_id", "enrollment.student_id"), headers(output));
    assertTrue(output.contains("1 row(s)"));
  }

  @Test
  void emptySelfJoinKeepsQualifiedCaseInsensitiveDuplicateHeaders() {
    var result = new QueryResult(schema("a", "ID", "b", "id"), List.of(), 0, "");
    String output = render(result);
    assertEquals(List.of("a.ID", "b.id"), headers(output));
    assertTrue(output.contains("0 row(s)"));
  }

  @Test
  void uniqueHeadersKeepTheirOriginalLabels() {
    var result =
        new QueryResult(schema("student", "student_id", "course", "course_id"), List.of(), 0, "");
    assertEquals(List.of("student_id", "course_id"), headers(render(result)));
  }

  @Test
  void columnsWithoutRelationKeepTheirName() {
    assertEquals(
        List.of("id", "id"),
        headers(render(new QueryResult(schema(null, "id", "", "id"), List.of(), 0, ""))));
  }

  private static Schema schema(String left, String leftName, String right, String rightName) {
    return new Schema(
        List.of(
            new Column(left, leftName, DataType.INTEGER, 0, false),
            new Column(right, rightName, DataType.INTEGER, 0, false)));
  }

  private static String render(QueryResult result) {
    StringWriter output = new StringWriter();
    new ResultRenderer().print(result, new PrintWriter(output));
    return output.toString();
  }

  private static List<String> headers(String output) {
    String line = output.lines().filter(value -> value.startsWith("|")).findFirst().orElseThrow();
    return Arrays.stream(line.substring(1, line.length() - 1).split("\\|", -1))
        .map(String::strip)
        .toList();
  }
}
