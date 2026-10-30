package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.Row;
import java.util.List;

// draf dari QP biar bisa compile, nanti ganti punya QP1
public record QueryResult(List<Column> columns, List<Row> rows, int affectedRows, String message) {}
