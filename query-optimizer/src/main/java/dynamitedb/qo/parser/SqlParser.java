package dynamitedb.qo.parser;

import dynamitedb.contracts.query.Statement;
import java.util.List;

/** Mengarahkan token ke parser statement dan parser ekspresi (QO-01). */
public class SqlParser {

  private final StatementParser statements;
  private final ExpressionParser expressions;

  public SqlParser(StatementParser statements, ExpressionParser expressions) {
    this.statements = statements;
    this.expressions = expressions;
  }

  public Statement parse(List<Token> tokens) {
    // TODO: tolak sintaks salah dengan error yang menyebut token atau posisinya
    return statements.parseStatement(tokens);
  }
}
