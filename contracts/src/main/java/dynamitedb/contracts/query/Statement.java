package dynamitedb.contracts.query;

public sealed interface Statement
    permits SelectStatement,
        InsertStatement,
        UpdateStatement,
        DeleteStatement,
        CreateIndexStatement,
        TransactionStatement {}
