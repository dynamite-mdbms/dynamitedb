package dynamitedb.contracts.query;

import dynamitedb.contracts.storage.IndexKind;

public record CreateIndexStatement(String index, String table, String column, IndexKind kind)
    implements Statement {}
