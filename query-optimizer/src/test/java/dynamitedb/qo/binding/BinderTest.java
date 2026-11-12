package dynamitedb.qo.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexStatement;
import dynamitedb.contracts.query.InsertStatement;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableSource;
import dynamitedb.contracts.query.UpdateStatement;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.qo.parser.ExpressionParser;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.parser.StatementParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BinderTest {

  private final Binder binder = new Binder(new FakeCatalog());

  @Test
  void expandsWildcardsWithAliasQualifiers() {
    var select = (SelectStatement) bind("SELECT * FROM student AS s");
    assertEquals(List.of(ref("s", "sid"), ref("s", "name"), ref("s", "gpa")), select.projection());

    var partial = (SelectStatement) bind("SELECT e.* FROM student AS s, enroll AS e");
    assertEquals(List.of(ref("e", "sid"), ref("e", "cid")), partial.projection());
  }

  @Test
  void qualifiesPredicateOrderByAndJoinCondition() {
    var select = (SelectStatement) bind("SELECT name FROM student WHERE gpa > 3 ORDER BY name");
    assertEquals(List.of(ref("student", "name")), select.projection());
    assertEquals(
        new BinaryExpression(column("student", "gpa"), BinaryOp.GT, number(3)),
        select.predicate().orElseThrow());
    assertEquals(ref("student", "name"), select.orderBy().get(0).column());

    var join =
        (SelectStatement)
            bind("SELECT cid FROM student AS s JOIN enroll AS e ON s.sid = e.sid AND gpa > 3");
    var condition = (BinaryExpression) ((JoinSource) join.from()).condition().orElseThrow();
    assertEquals(
        new BinaryExpression(column("s", "sid"), BinaryOp.EQ, column("e", "sid")),
        condition.left());
    assertEquals(List.of(ref("e", "cid")), join.projection());
  }

  @Test
  void naturalJoinSharesCommonColumnsOnce() {
    var select = (SelectStatement) bind("SELECT * FROM student NATURAL JOIN enroll");
    assertEquals(
        List.of(
            ref("student", "sid"),
            ref("student", "name"),
            ref("student", "gpa"),
            ref("enroll", "cid")),
        select.projection());

    var shared = (SelectStatement) bind("SELECT sid FROM student NATURAL JOIN enroll");
    assertEquals(List.of(ref("student", "sid")), shared.projection());
  }

  @Test
  void naturalJoinCommonColumnResolvesThroughEitherTable() {
    for (String qualifier : List.of("student", "enroll")) {
      var select =
          (SelectStatement)
              bind(
                  "SELECT "
                      + qualifier
                      + ".sid FROM student NATURAL JOIN enroll WHERE "
                      + qualifier
                      + ".sid = 1 ORDER BY "
                      + qualifier
                      + ".sid");
      assertEquals(List.of(ref("student", "sid")), select.projection(), qualifier);
      assertEquals(
          new BinaryExpression(column("student", "sid"), BinaryOp.EQ, number(1)),
          select.predicate().orElseThrow(),
          qualifier);
      assertEquals(ref("student", "sid"), select.orderBy().get(0).column(), qualifier);
    }

    var aliased =
        (SelectStatement) bind("SELECT E.Sid, e.cid FROM student AS s NATURAL JOIN enroll AS e");
    assertEquals(List.of(ref("s", "sid"), ref("e", "cid")), aliased.projection());
  }

  @Test
  void derivesOutputSchemaFromBoundProjectionAndAliases() {
    Statement bound = bind("SELECT e.cid, s.name FROM student AS s, enroll AS e");

    assertEquals(
        new Schema(
            List.of(
                new Column("e", "cid", DataType.INTEGER, 0, false),
                new Column("s", "name", DataType.VARCHAR, 5, false))),
        binder.outputSchema(bound));

    Statement naturalJoin = bind("SELECT * FROM student NATURAL JOIN enroll");
    assertEquals(
        new Schema(
            List.of(
                new Column("student", "sid", DataType.INTEGER, 0, false),
                new Column("student", "name", DataType.VARCHAR, 5, false),
                new Column("student", "gpa", DataType.FLOAT, 0, true),
                new Column("enroll", "cid", DataType.INTEGER, 0, false))),
        binder.outputSchema(naturalJoin));

    assertEquals(new Schema(List.of()), binder.outputSchema(bind("BEGIN")));
  }

  @Test
  void bindsWritesAndPassesThroughTransactions() {
    bind("INSERT INTO student VALUES (1, 'Ann', 3.5)");
    bind("INSERT INTO student VALUES (1, 'Ann', 3)");

    var update = (UpdateStatement) bind("UPDATE student SET gpa = gpa * 2 WHERE sid = 1");
    assertEquals(ref("student", "gpa"), update.target());
    assertEquals(
        new BinaryExpression(column("student", "gpa"), BinaryOp.MUL, number(2)),
        update.expression());

    bind("DELETE FROM student WHERE name = 'Ann'");
    bind("CREATE INDEX idx_gpa ON student(gpa)");

    Statement begin = parse("BEGIN");
    assertSame(begin, binder.bind(begin));
  }

  @Test
  void matchesIdentifiersIgnoringCaseAndKeepsCatalogSpelling() {
    var select = (SelectStatement) bind("SELECT NAME FROM Student WHERE STUDENT.Gpa > 3");
    assertEquals(new TableSource("student", "student"), select.from());
    assertEquals(List.of(ref("student", "name")), select.projection());
    assertEquals(
        new BinaryExpression(column("student", "gpa"), BinaryOp.GT, number(3)),
        select.predicate().orElseThrow());

    var aliased = (SelectStatement) bind("SELECT S.Sid FROM STUDENT AS s");
    assertEquals(new TableSource("student", "s"), aliased.from());
    assertEquals(List.of(ref("s", "sid")), aliased.projection());

    assertEquals(
        "student", ((InsertStatement) bind("INSERT INTO Student VALUES (1, 'A', 1)")).table());
    var index = (CreateIndexStatement) bind("CREATE INDEX idx_name ON STUDENT(Name)");
    assertEquals("student", index.table());
    assertEquals("name", index.column());
  }

  @Test
  void rejectsInvalidReferencesAndTypes() {
    for (String sql :
        List.of(
            "SELECT * FROM missing",
            "SELECT age FROM student",
            "SELECT sid FROM student, enroll",
            "SELECT * FROM student, student",
            "SELECT * FROM student AS s, enroll AS S",
            "CREATE INDEX IDX_SID ON enroll(cid)",
            "SELECT x.* FROM student",
            "SELECT * FROM student NATURAL JOIN tagged",
            "SELECT name FROM student WHERE name > 3",
            "SELECT name FROM student WHERE gpa",
            "SELECT name FROM student WHERE name + 1 = 2",
            "SELECT * FROM student AS s JOIN enroll AS e ON s.sid",
            "INSERT INTO student VALUES (1, 'Ann')",
            "INSERT INTO student VALUES ('1', 'Ann', 3.5)",
            "INSERT INTO student VALUES (1.5, 'Ann', 3.5)",
            "INSERT INTO student VALUES (1, 'Annabel', 3.5)",
            "UPDATE student SET gpa = 'high'",
            "UPDATE student SET name = sid + 1",
            "UPDATE student SET age = 1",
            "DELETE FROM student WHERE missing = 1",
            "CREATE INDEX idx_x ON student(age)",
            "CREATE INDEX idx_sid ON enroll(cid)")) {
      var error = assertThrows(DbmsException.class, () -> bind(sql), sql);
      assertEquals(ErrorCode.SQL_BIND, error.getCode(), sql);
    }
  }

  @Test
  void limitsTextByUtf8BytesLikeStorage() {
    bind("INSERT INTO student VALUES (1, 'Annab', 3.5)");
    bind("INSERT INTO student VALUES (1, 'éé', 3.5)");

    var error =
        assertThrows(DbmsException.class, () -> bind("INSERT INTO student VALUES (1, 'ééé', 3.5)"));
    assertEquals(ErrorCode.SQL_BIND, error.getCode());
    assertTrue(error.getMessage().contains("student.name"), error.getMessage());
    assertThrows(DbmsException.class, () -> bind("UPDATE student SET name = 'ééé'"));
  }

  @Test
  void rejectsNullForNonNullableColumn() {
    var insert =
        new InsertStatement(
            "student",
            List.of(
                new LiteralExpression(NullValue.INSTANCE),
                new LiteralExpression(new TextValue("Ann")),
                new LiteralExpression(NullValue.INSTANCE)));
    var error = assertThrows(DbmsException.class, () -> binder.bind(insert));
    assertEquals(ErrorCode.SQL_BIND, error.getCode());
  }

  private Statement bind(String sql) {
    return binder.bind(parse(sql));
  }

  private static Statement parse(String sql) {
    return new SqlParser(new StatementParser(), new ExpressionParser())
        .parse(new Lexer().tokenize(sql));
  }

  private static ColumnRef ref(String relation, String column) {
    return new ColumnRef(Optional.of(relation), column);
  }

  private static ColumnExpression column(String relation, String column) {
    return new ColumnExpression(ref(relation, column));
  }

  private static LiteralExpression number(int value) {
    return new LiteralExpression(new IntValue(value));
  }

  private static final class FakeCatalog implements CatalogAccess {
    private final Map<String, Schema> schemas =
        Map.of(
            "student",
            new Schema(
                List.of(
                    new Column("student", "sid", DataType.INTEGER, 0, false),
                    new Column("student", "name", DataType.VARCHAR, 5, false),
                    new Column("student", "gpa", DataType.FLOAT, 0, true))),
            "enroll",
            new Schema(
                List.of(
                    new Column("enroll", "sid", DataType.INTEGER, 0, false),
                    new Column("enroll", "cid", DataType.INTEGER, 0, false))),
            "tagged",
            new Schema(List.of(new Column("tagged", "sid", DataType.CHAR, 4, false))));

    @Override
    public List<String> listTables() {
      return List.copyOf(schemas.keySet());
    }

    @Override
    public long getTableId(String table) {
      return 1;
    }

    @Override
    public Schema getSchema(String table) {
      return schemas.get(table);
    }

    @Override
    public TableStatistics getStatistics(String table) {
      return new TableStatistics(0, 0, 0, Map.of());
    }

    @Override
    public List<IndexMetadata> listIndexes(String table) {
      return table.equals("student")
          ? List.of(new IndexMetadata("idx_sid", "student", "sid", IndexKind.HASH))
          : List.of();
    }
  }
}
