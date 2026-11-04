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
import dynamitedb.contracts.query.DeleteStatement;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.InsertStatement;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionStatement;
import dynamitedb.contracts.query.UpdateStatement;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IntValue;
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
          CREATE TABLE student                        | Expected INDEX at offset 7 near 'TABLE'
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
