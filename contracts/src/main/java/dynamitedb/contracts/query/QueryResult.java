package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import java.util.List;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record QueryResult(Schema schema, List<Row> rows, long affectedRows, String message) {}
