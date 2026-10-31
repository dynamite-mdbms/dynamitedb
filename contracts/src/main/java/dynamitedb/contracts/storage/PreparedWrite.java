package dynamitedb.contracts.storage;

import dynamitedb.contracts.transaction.TransactionId;

/** Hasil tahap prepare, belum mengubah heap maupun index. */
public record PreparedWrite(
    TransactionId tx,
    String table,
    RecordId rid,
    RecordImage before,
    RecordImage after,
    WriteReservationId reservation) {}
