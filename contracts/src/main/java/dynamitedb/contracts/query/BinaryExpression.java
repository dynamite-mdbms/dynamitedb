package dynamitedb.contracts.query;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record BinaryExpression(Expression left, BinaryOp operator, Expression right)
    implements Expression {}
