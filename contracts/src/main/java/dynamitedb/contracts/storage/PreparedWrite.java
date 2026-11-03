package dynamitedb.contracts.storage;

import dynamitedb.contracts.transaction.TransactionId;

public record PreparedWrite(
    TransactionId tx,
    String table,
    RecordId rid,
    RecordImage before,
    RecordImage after,
    WriteReservationId reservation) {}
