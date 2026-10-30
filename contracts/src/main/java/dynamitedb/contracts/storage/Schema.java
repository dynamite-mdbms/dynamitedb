package dynamitedb.contracts.storage;

import java.util.List;

// draf dari QP biar bisa compile, nanti ganti punya SM
public record Schema(String table, List<Column> columns) {}
