package dynamitedb.contracts.storage;

import dynamitedb.contracts.transaction.TransactionId;

// draf dari QP biar bisa compile, nanti ganti punya SM
public record PreparedWrite(
    TransactionId tx,
    String table,
    RecordId rid,
    RecordImage before,
    RecordImage after,
    WriteReservationId reservation) {}
