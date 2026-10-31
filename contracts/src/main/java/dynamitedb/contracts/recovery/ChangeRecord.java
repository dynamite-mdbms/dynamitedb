package dynamitedb.contracts.recovery;

import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.transaction.TransactionId;

public record ChangeRecord(
    TransactionId tx, String table, RecordId rid, RecordImage before, RecordImage after) {}
