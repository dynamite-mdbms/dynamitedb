package dynamitedb.contracts.query;

import java.util.Optional;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record ColumnRef(Optional<String> relation, String column) {}
