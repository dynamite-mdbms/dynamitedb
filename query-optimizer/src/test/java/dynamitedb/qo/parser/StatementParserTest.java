package dynamitedb.qo.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexStatement;
import dynamitedb.contracts.query.CreateTableStatement;
import dynamitedb.contracts.query.DeleteStatement;
import dynamitedb.contracts.query.DropTableStatement;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.InsertStatement;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionStatement;
import dynamitedb.contracts.query.UpdateStatement;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.DropBehavior;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.TextValue;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatementParserTest {

  private final Lexer lexer = new Lexer();
  private final StatementParser parser = new StatementParser(new ExpressionParser());

  private Statement parse(String sql) {
    return parser.parseStatement(lexer.tokenize(sql));
  }

  private static Expression col(String name) {
    return new ColumnExpression(new ColumnRef(Optional.empty(), name));
  }

  private static Expression lit(int value) {
    return new LiteralExpression(new IntValue(value));
  }

  @Test
  void parsesInsertWithExpressionValues() {
    assertEquals(
        new InsertStatement(
            "student",
            List.of(
                lit(1),
                new LiteralExpression(new TextValue("Ann, B")),
                new BinaryExpression(
                    new LiteralExpression(new FloatValue(3.5)), BinaryOp.MUL, lit(2)),
                new BinaryExpression(lit(1), BinaryOp.ADD, lit(2)))),
        parse("INSERT INTO student VALUES (1, 'Ann, B', 3.5 * 2, (1 + 2));"));
  }

  @Test
  void parsesNegativeNumbersInInsertAndUpdate() {
    Expression negativeFloat = new LiteralExpression(new FloatValue(-2.5));
    assertEquals(
        new InsertStatement("student", List.of(lit(-1), negativeFloat)),
        parse("INSERT INTO student VALUES (-1, -2.5);"));
    assertEquals(
        new UpdateStatement(
            "student",
            new ColumnRef(Optional.empty(), "gpa"),
            negativeFloat,
            Optional.of(new BinaryExpression(col("student_id"), BinaryOp.EQ, lit(-1)))),
        parse("UPDATE student SET gpa = -2.5 WHERE student_id = -1;"));
  }

  @Test
  void parsesUpdateWithAndWithoutWhere() {
    Expression raise =
        new BinaryExpression(
            col("salary"), BinaryOp.MUL, new LiteralExpression(new FloatValue(1.05)));
    assertEquals(
        new UpdateStatement(
            "employee", new ColumnRef(Optional.empty(), "salary"), raise, Optional.empty()),
        parse("UPDATE employee SET salary = salary * 1.05"));
    assertEquals(
        new UpdateStatement(
            "employee",
            new ColumnRef(Optional.of("employee"), "salary"),
            raise,
            Optional.of(
                new BinaryExpression(
                    col("department"), BinaryOp.EQ, new LiteralExpression(new TextValue("RnD"))))),
        parse("update employee set employee.salary = salary * 1.05 where department = 'RnD';"));
  }

  @Test
  void parsesDeleteWithAndWithoutWhere() {
    assertEquals(new DeleteStatement("student", Optional.empty()), parse("DELETE FROM student;"));
    assertEquals(
        new DeleteStatement(
            "student", Optional.of(new BinaryExpression(col("gpa"), BinaryOp.LT, lit(2)))),
        parse("DELETE FROM student WHERE gpa < 2"));
  }

  @Test
  void parsesTransactionCommands() {
    TransactionStatement begin = new TransactionStatement(TransactionCommand.BEGIN);
    assertEquals(begin, parse("BEGIN"));
    assertEquals(begin, parse("begin transaction;"));
    assertEquals(new TransactionStatement(TransactionCommand.COMMIT), parse("COMMIT"));
    assertEquals(new TransactionStatement(TransactionCommand.COMMIT), parse("COMMIT;"));
    assertEquals(new TransactionStatement(TransactionCommand.ABORT), parse("ABORT;"));
  }

  @Test
  void parsesCreateIndexWithDefaultAndExplicitKind() {
    assertEquals(
        new CreateIndexStatement("idx_gpa", "student", "gpa", IndexKind.HASH),
        parse("CREATE INDEX idx_gpa ON student(gpa);"));
    assertEquals(
        new CreateIndexStatement("idx_gpa", "student", "gpa", IndexKind.HASH),
        parse("CREATE INDEX idx_gpa ON student(gpa) USING hash"));
    assertEquals(
        new CreateIndexStatement("idx_gpa", "student", "gpa", IndexKind.B_PLUS_TREE),
        parse("CREATE INDEX idx_gpa ON student(gpa) USING B_PLUS_TREE;"));
  }

  @Test
  void parsesCreateTableWithTypesAndKeys() {
    assertEquals(
        new CreateTableStatement(
            "enrollment",
            List.of(
                new Column("enrollment", "id", DataType.INTEGER, 0, true),
                new Column("enrollment", "score", DataType.FLOAT, 0, true),
                new Column("enrollment", "grade", DataType.CHAR, 2, true),
                new Column("enrollment", "note", DataType.VARCHAR, 50, true)),
            List.of(new PrimaryKey(List.of("id"))),
            List.of(new ForeignKey("enrollment", List.of("id"), "student", List.of("student_id")))),
        parse(
            "create table enrollment (id integer, score Float, grade CHAR(2), note varchar(50),"
                + " primary key (id), foreign key (id) references student(student_id));"));
    assertEquals(
        new CreateTableStatement(
            "t",
            List.of(
                new Column("t", "a", DataType.INTEGER, 0, true),
                new Column("t", "b", DataType.INTEGER, 0, true)),
            List.of(new PrimaryKey(List.of("a")), new PrimaryKey(List.of("b"))),
            List.of()),
        parse("CREATE TABLE t (a INTEGER PRIMARY KEY, b INTEGER PRIMARY KEY)"));
    assertEquals(
        new CreateTableStatement(
            "t",
            List.of(
                new Column("t", "a", DataType.INTEGER, 0, true),
                new Column("t", "b", DataType.INTEGER, 0, true)),
            List.of(new PrimaryKey(List.of("a", "b"))),
            List.of(new ForeignKey("t", List.of("b", "a"), "s", List.of("x", "y")))),
        parse(
            "CREATE TABLE t (a INTEGER, b INTEGER, PRIMARY KEY (a, b),"
                + " FOREIGN KEY (b, a) REFERENCES s(x, y))"));
  }

  @Test
  void parsesDropTableWithRestrictAsDefault() {
    assertEquals(
        new DropTableStatement("student", DropBehavior.RESTRICT), parse("DROP TABLE student;"));
    assertEquals(
        new DropTableStatement("student", DropBehavior.RESTRICT),
        parse("drop table student restrict"));
    assertEquals(
        new DropTableStatement("student", DropBehavior.CASCADE),
        parse("DROP TABLE student CASCADE;"));
  }

  // Pesan lengkap dicek agar terbukti aturan mana yang menolak input dan di offset mana
  // (fail-fast).
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      textBlock =
          """
          UPDATE student;                             | Expected SET at end of input
          UPDATE student salary = 1                   | Expected SET at offset 15 near 'salary'
          UPDATE student SET;                         | Expected a column name at end of input
          UPDATE student SET gpa 4                    | Expected = at offset 23 near '4'
          UPDATE student SET gpa = ;                  | Expected expression at end of input
          UPDATE student SET gpa = WHERE id = 1       | Expected expression at offset 25 near 'WHERE'
          UPDATE student SET gpa = 4 WHERE;           | Expected expression at end of input
          UPDATE 5 SET a = 1                          | Expected a table name at offset 7 near '5'
          INSERT student VALUES (1);                  | Expected INTO at offset 7 near 'student'
          INSERT INTO student (1);                    | Expected VALUES at offset 20 near '('
          INSERT INTO VALUES (1)                      | Expected a table name at offset 12 near 'VALUES'
          INSERT INTO student VALUES (1, );           | Expected expression at offset 31 near ')'
          INSERT INTO student VALUES (1               | Expected ')' to close VALUES list opened at offset 27 at end of input
          INSERT INTO student VALUES (1) 2            | Unexpected token after INSERT statement at offset 31 near '2'
          DELETE student;                             | Expected FROM at offset 7 near 'student'
          DELETE FROM student gpa < 2                 | Expected WHERE at offset 20 near 'gpa'
          BEGIN SOMETHING;                            | Unexpected token after BEGIN statement at offset 6 near 'SOMETHING'
          COMMIT; ABORT;                              | Unexpected token after COMMIT statement at offset 6 near ';'
          COMMIT;;                                    | Unexpected token after COMMIT statement at offset 6 near ';'
          ;                                           | Expected an SQL statement at offset 0 near ';'
          CREATE INDEX idx student(id);               | Expected ON at offset 17 near 'student'
          CREATE INDEX idx ON student(id) USING BTREE | Expected HASH or B_PLUS_TREE after USING at offset 38 near 'BTREE'
          CREATE VIEW v                               | Expected INDEX or TABLE at offset 7 near 'VIEW'
          CREATE TABLE student                        | Expected ( at end of input
          CREATE TABLE (id INTEGER)                   | Expected a table name at offset 13 near '('
          CREATE TABLE t ()                           | Expected a column name at offset 16 near ')'
          CREATE TABLE t (id)                         | Expected a column type INTEGER, FLOAT, CHAR(n), or VARCHAR(n) at offset 18 near ')'
          CREATE TABLE t (id TEXT)                    | Expected a column type INTEGER, FLOAT, CHAR(n), or VARCHAR(n) at offset 19 near 'TEXT'
          CREATE TABLE t (name VARCHAR)               | Expected ( at offset 28 near ')'
          CREATE TABLE t (name CHAR(n))               | Expected an integer length for CHAR at offset 26 near 'n'
          CREATE TABLE t (name CHAR(2.5))             | Expected an integer length for CHAR at offset 26 near '2.5'
          CREATE TABLE t (name CHAR(9999999999))      | Expected an integer length for CHAR at offset 26 near '9999999999'
          CREATE TABLE t (id INTEGER(4))              | Expected ) at offset 26 near '('
          CREATE TABLE t (id INTEGER,)                | Expected a column name at offset 27 near ')'
          CREATE TABLE t (id INTEGER                  | Expected ) at end of input
          CREATE TABLE t (id INTEGER PRIMARY)         | Expected KEY at offset 34 near ')'
          CREATE TABLE t (id INTEGER, PRIMARY KEY id) | Expected ( at offset 40 near 'id'
          CREATE TABLE t (a INTEGER, PRIMARY KEY (a,))   | Expected a column name at offset 42 near ')'
          CREATE TABLE t (a INTEGER, PRIMARY KEY (a b))  | Expected ) at offset 42 near 'b'
          CREATE TABLE t (a INTEGER, b INTEGER, FOREIGN KEY (a, b) REFERENCES s(x)) | FOREIGN KEY and REFERENCES need the same number of columns at offset 69 near '('
          CREATE TABLE t (a INTEGER, FOREIGN KEY (a) s(id)) | Expected REFERENCES at offset 43 near 's'
          CREATE TABLE t (a INTEGER) x                | Unexpected token after CREATE statement at offset 27 near 'x'
          DROP student                                | Expected TABLE at offset 5 near 'student'
          DROP TABLE;                                 | Expected a table name at end of input
          DROP TABLE student RESTRICT CASCADE         | Unexpected token after DROP statement at offset 28 near 'CASCADE'
          SELECT * FROM student                       | Expected a supported statement keyword at offset 0 near 'SELECT'
          """)
  void rejectsInvalidStatementAtFirstError(String sql, String message) {
    DbmsException failure = assertThrows(DbmsException.class, () -> parse(sql));
    assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
    assertEquals(message, failure.getMessage());
  }

  @Test
  void rejectsEmptyTokenList() {
    for (List<Token> tokens : Arrays.asList(List.<Token>of(), null)) {
      DbmsException failure =
          assertThrows(DbmsException.class, () -> parser.parseStatement(tokens));
      assertEquals(ErrorCode.SQL_SYNTAX, failure.getCode());
      assertEquals("Expected an SQL statement at end of input", failure.getMessage());
    }
  }
}
