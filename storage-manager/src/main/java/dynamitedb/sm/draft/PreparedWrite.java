package dynamitedb.sm.draft;

/** Temporary contract: Two-phase write description; preparation must not mutate heap or indexes. */
public record PreparedWrite(
    TransactionId tx,
    String table,
    RecordId rid,
    RecordImage before,
    RecordImage after,
    WriteReservationId reservation) {}
