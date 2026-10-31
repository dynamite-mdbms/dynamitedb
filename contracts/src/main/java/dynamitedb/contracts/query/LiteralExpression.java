package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Value;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record LiteralExpression(Value value) implements Expression {}
