package dynamitedb.qo.parser;

import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.storage.BooleanValue;
import java.util.List;

/** Membentuk pohon ekspresi sesuai prioritas operator (QO-04). */
public class ExpressionParser {

  public Expression parseExpression(List<Token> tokens) {
    // TODO: kolom, literal, aritmatika, perbandingan, AND, dan kurung
    return new LiteralExpression(new BooleanValue(true));
  }
}
