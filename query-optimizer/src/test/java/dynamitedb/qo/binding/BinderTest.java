package dynamitedb.qo.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.AggregateExpression;
import dynamitedb.contracts.query.AggregateFunction;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexStatement;
import dynamitedb.contracts.query.CreateTableStatement;
import dynamitedb.contracts.query.DropTableStatement;
import dynamitedb.contracts.query.InsertStatement;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableSource;
import dynamitedb.contracts.query.UpdateStatement;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.DropBehavior;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.PrimaryKey;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
  void keepsLimitAndLeavesOutputSchemaUnchanged() {
    var limited = (SelectStatement) bind("SELECT name FROM student ORDER BY gpa DESC LIMIT 3");
    assertEquals(Optional.of(3L), limited.limit());
    assertEquals(ref("student", "gpa"), limited.orderBy().get(0).column());
    assertEquals(
        binder.outputSchema(bind("SELECT name FROM student")), binder.outputSchema(limited));
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

    bind("UPDATE student SET sid = sid + 1");
    bind("UPDATE student SET gpa = sid * 2");
    var multi = (UpdateStatement) bind("UPDATE student SET sid = sid + 1, gpa = gpa * 2");
    assertEquals(2, multi.assignments().size());
    assertEquals(ref("student", "sid"), multi.assignments().get(0).target());
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
  void bindsExpressionAliasesForOrderByButKeepsWhereInInputScope() {
    var select =
        (SelectStatement) bind("SELECT sid + 1 AS next_sid FROM student ORDER BY next_sid");
    assertEquals(Optional.of("next_sid"), select.items().get(0).alias());
    assertEquals(new ColumnRef(Optional.empty(), "next_sid"), select.orderBy().get(0).column());

    DbmsException unknownAlias =
        assertThrows(
            DbmsException.class,
            () -> bind("SELECT sid + 1 AS next_sid FROM student WHERE next_sid > 2"));
    assertEquals(ErrorCode.SQL_BIND, unknownAlias.getCode());

    DbmsException duplicate =
        assertThrows(DbmsException.class, () -> bind("SELECT sid AS x, gpa AS x FROM student"));
    assertEquals(ErrorCode.SQL_BIND, duplicate.getCode());
    assertTrue(duplicate.getMessage().contains("Duplicate output alias"));
  }

  @Test
  void bindsAggregatesAndRequiresEverySelectedColumnToBeGrouped() {
    var aggregate =
        (SelectStatement)
            bind(
                "SELECT name AS dept, COUNT(*) AS total, AVG(gpa) AS mean "
                    + "FROM student GROUP BY name HAVING COUNT(*) > 1 ORDER BY total");
    assertEquals(List.of(ref("student", "name")), aggregate.groupBy());
    assertEquals(
        new AggregateExpression(AggregateFunction.COUNT, Optional.empty()),
        aggregate.items().get(1).expression());
    assertEquals(DataType.INTEGER, binder.outputSchema(aggregate).columns().get(1).dataType());
    assertEquals(DataType.FLOAT, binder.outputSchema(aggregate).columns().get(2).dataType());

    DbmsException ungrouped =
        assertThrows(
            DbmsException.class, () -> bind("SELECT name, COUNT(*) FROM student GROUP BY sid"));
    assertEquals(ErrorCode.SQL_BIND, ungrouped.getCode());
    assertTrue(ungrouped.getMessage().contains("not grouped or aggregated"));
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
            "UPDATE student SET sid = sid * 1.5",
            "UPDATE student SET sid = gpa",
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
  void rejectsDuplicateUpdateTargets() {
    DbmsException failure =
        assertThrows(DbmsException.class, () -> bind("UPDATE student SET sid = 1, SID = 2"));
    assertEquals(ErrorCode.SQL_BIND, failure.getCode());
    assertTrue(failure.getMessage().contains("Duplicate UPDATE target"));
  }
  
  void bindsCreateTableKeysToCanonicalNames() {
    var create =
        (CreateTableStatement)
            bind(
                "CREATE TABLE Grade (Id INTEGER, sid INTEGER, parent INTEGER, mark CHAR(65535),"
                    + " PRIMARY KEY (ID), FOREIGN KEY (SID) REFERENCES STUDENT(SID),"
                    + " FOREIGN KEY (parent) REFERENCES grade(id))");

    assertEquals("Grade", create.table());
    assertEquals(List.of(new PrimaryKey(List.of("Id"))), create.primaryKeys());
    assertEquals(
        List.of(
            new ForeignKey("Grade", List.of("sid"), "student", List.of("sid")),
            new ForeignKey("Grade", List.of("parent"), "Grade", List.of("Id"))),
        create.foreignKeys());
    assertEquals(new Column("Grade", "Id", DataType.INTEGER, 0, false), create.columns().get(0));
    assertTrue(create.columns().get(1).nullable());
  }

  @Test
  void bindsCompositeKeysAgainstCatalogPrimaryKey() {
    var create =
        (CreateTableStatement)
            bind(
                "CREATE TABLE g (a INTEGER, b INTEGER, PRIMARY KEY (B, A),"
                    + " FOREIGN KEY (a, b) REFERENCES ENROLL(SID, CID))");

    assertEquals(List.of(new PrimaryKey(List.of("b", "a"))), create.primaryKeys());
    assertEquals(
        List.of(new ForeignKey("g", List.of("a", "b"), "enroll", List.of("sid", "cid"))),
        create.foreignKeys());
    assertTrue(create.columns().stream().noneMatch(Column::nullable));
  }

  @Test
  void rejectsDropRestrictOfReferencedTableLikeStorage() {
    for (String sql : List.of("DROP TABLE student", "DROP TABLE Student RESTRICT")) {
      var error = assertThrows(DbmsException.class, () -> bind(sql), sql);
      assertEquals(ErrorCode.INVALID_STATE, error.getCode(), sql);
      assertEquals(
          "Table student is referenced by a FOREIGN KEY on enroll; use DROP TABLE ... CASCADE",
          error.getMessage(),
          sql);
    }
  }

  @Test
  void bindsDropTableToCatalogName() {
    assertEquals(
        new DropTableStatement("student", DropBehavior.CASCADE),
        bind("DROP TABLE STUDENT CASCADE"));
    // enroll only references itself, which is dropped with it.
    assertEquals(
        new DropTableStatement("enroll", DropBehavior.RESTRICT), bind("DROP TABLE enroll"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          CREATE TABLE Student (id INTEGER)                    | Table already exists: Student
          CREATE TABLE t (id INTEGER, ID FLOAT)                 | Duplicate column: ID
          CREATE TABLE t (PRIMARY KEY (id))                     | A table requires between 1 and 1024 columns
          CREATE TABLE t (a INTEGER PRIMARY KEY, b INTEGER, PRIMARY KEY (b)) | Multiple primary keys for table t
          CREATE TABLE t (a INTEGER, PRIMARY KEY (b))           | PRIMARY KEY column is not in the table: b
          CREATE TABLE t (a INTEGER, FOREIGN KEY (b) REFERENCES student(sid)) | FOREIGN KEY column is not in the table: b
          CREATE TABLE t (a INTEGER, FOREIGN KEY (a) REFERENCES missing(id))  | Unknown table: missing
          CREATE TABLE t (a INTEGER, FOREIGN KEY (a) REFERENCES student(age)) | Unknown column: age
          CREATE TABLE t (a FLOAT, FOREIGN KEY (a) REFERENCES student(sid))   | FOREIGN KEY a (FLOAT) does not match student.sid (INTEGER)
          CREATE TABLE t (a INTEGER PRIMARY KEY, b INTEGER, FOREIGN KEY (a) REFERENCES t(b)) | FOREIGN KEY must reference the PRIMARY KEY of t
          CREATE TABLE t (a VARCHAR(5), FOREIGN KEY (a) REFERENCES student(name)) | FOREIGN KEY must reference the PRIMARY KEY of student
          CREATE TABLE t (a CHAR(4), FOREIGN KEY (a) REFERENCES tagged(sid))   | FOREIGN KEY must reference the PRIMARY KEY of tagged
          CREATE TABLE t (a INTEGER, FOREIGN KEY (a) REFERENCES enroll(sid))   | FOREIGN KEY must reference the PRIMARY KEY of enroll
          CREATE TABLE t (a INTEGER, b INTEGER, FOREIGN KEY (a, b) REFERENCES enroll(cid, sid)) | FOREIGN KEY must reference the PRIMARY KEY of enroll
          CREATE TABLE t (a INTEGER, PRIMARY KEY (a, A))        | PRIMARY KEY repeats column a
          CREATE TABLE t (a INTEGER, FOREIGN KEY (a, a) REFERENCES enroll(sid, cid)) | FOREIGN KEY repeats column a
          CREATE TABLE t (a CHAR(0))                            | Invalid length 0 for column a, expected 1 to 65535
          CREATE TABLE t (a VARCHAR(65536))                     | Invalid length 65536 for column a, expected 1 to 65535
          CREATE TABLE tabél (a INTEGER)                        | Invalid identifier (ASCII letters, digits, '_', at most 128): tabél
          DROP TABLE missing                                    | Unknown table: missing
          """)
  void rejectsInvalidTableDdl(String sql, String message) {
    var error = assertThrows(DbmsException.class, () -> bind(sql), sql);
    assertEquals(ErrorCode.SQL_BIND, error.getCode(), sql);
    assertEquals(message, error.getMessage(), sql);
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
  void bindsNegativeNumbersByColumnType() {

    var insert = (InsertStatement) bind("INSERT INTO student VALUES (-1, 'Ann', -2.5)");
    assertEquals(number(-1), insert.values().get(0));
    assertEquals(new LiteralExpression(new FloatValue(-2.5)), insert.values().get(2));
    bind("INSERT INTO student VALUES (-1, 'Ann', -3)");
    bind("UPDATE student SET gpa = -1 WHERE sid = -1");
    bind("UPDATE student SET gpa = gpa - -1.5 WHERE sid > -2");

    for (String sql :
        List.of(
            "INSERT INTO student VALUES (-1.5, 'Ann', 3.5)",
            "INSERT INTO student VALUES (1, -1, 3.5)",
            "UPDATE student SET name = -1",
            "UPDATE student SET sid = -2.5",
            "SELECT name FROM student WHERE name = -1")) {
      var error = assertThrows(DbmsException.class, () -> bind(sql), sql);
      assertEquals(ErrorCode.SQL_BIND, error.getCode(), sql);
    }
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

  @Test
  void bindsSqlNullLiteralsForNullableColumns() {
    var insert = (InsertStatement) bind("INSERT INTO student VALUES (1, 'Ann', NULL);");
    assertEquals(new LiteralExpression(NullValue.INSTANCE), insert.values().get(2));
    bind("UPDATE student SET gpa = NULL WHERE sid = 1;");
    bind("SELECT sid FROM student WHERE gpa = NULL;");
    bind("SELECT sid FROM student WHERE gpa IS NULL AND name IS NOT NULL;");
    bind("DELETE FROM student WHERE gpa IS NULL;");

    var error =
        assertThrows(
            DbmsException.class, () -> bind("INSERT INTO student VALUES (NULL, 'Ann', 3.5);"));
    assertEquals(ErrorCode.SQL_BIND, error.getCode());
    assertTrue(error.getMessage().contains("Column sid does not accept NULL"));
    error = assertThrows(DbmsException.class, () -> bind("UPDATE student SET name = NULL;"));
    assertEquals(ErrorCode.SQL_BIND, error.getCode());
    assertTrue(error.getMessage().contains("Column name does not accept NULL"));
  }

  @Test
  void bindsIsNullOnEveryColumnTypeAndRejectsItAsAWrittenValue() {
    bind("SELECT sid FROM student WHERE sid IS NULL AND name IS NOT NULL AND gpa IS NULL;");
    bind("SELECT sid FROM student WHERE NULL IS NULL;");
    bind("SELECT sid FROM tagged WHERE sid IS NOT NULL;");


    var error =
        assertThrows(DbmsException.class, () -> bind("UPDATE student SET gpa = gpa IS NULL;"));
    assertEquals(ErrorCode.SQL_BIND, error.getCode());

    error =
        assertThrows(DbmsException.class, () -> bind("SELECT sid FROM student WHERE gpa IS 3;"));
    assertEquals(ErrorCode.SQL_SYNTAX, error.getCode());
  }

  @Test
  void nullArithmeticInConditionsFollowsTheNumericRules() {
    bind("SELECT sid FROM student WHERE NULL + 1 > 0;");
    bind("SELECT sid FROM student WHERE gpa + NULL IS NULL;");
    var error =
        assertThrows(DbmsException.class, () -> bind("SELECT sid FROM student WHERE NULL + 1;"));
    assertEquals(ErrorCode.SQL_BIND, error.getCode());
    error =
        assertThrows(
            DbmsException.class, () -> bind("SELECT sid FROM student WHERE name + NULL > 1;"));
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
    // tagged has no PRIMARY KEY; enroll has a composite one and references itself.
    private final Map<String, PrimaryKey> primaryKeys =
        Map.of(
            "student", new PrimaryKey(List.of("sid")),
            "enroll", new PrimaryKey(List.of("sid", "cid")));
    private final List<ForeignKey> foreignKeys =
        List.of(
            new ForeignKey("enroll", List.of("sid"), "student", List.of("sid")),
            new ForeignKey("enroll", List.of("sid", "cid"), "enroll", List.of("sid", "cid")));

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

    @Override
    public Optional<PrimaryKey> getPrimaryKey(String table) {
      return Optional.ofNullable(primaryKeys.get(table));
    }

    @Override
    public List<ForeignKey> listForeignKeys(String table) {
      return foreignKeys.stream().filter(key -> key.table().equals(table)).toList();
    }

    @Override
    public List<ForeignKey> listReferencingForeignKeys(String table) {
      return foreignKeys.stream().filter(key -> key.referencedTable().equals(table)).toList();
    }
  }
}
