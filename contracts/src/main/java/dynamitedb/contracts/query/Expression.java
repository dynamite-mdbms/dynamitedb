package dynamitedb.contracts.query;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public sealed interface Expression
    permits AggregateExpression,
        LiteralExpression,
        ColumnExpression,
        BinaryExpression,
        InSubqueryExpression,
        ExistsSubqueryExpression,
        ScalarSubqueryExpression,
        UnaryExpression {}
