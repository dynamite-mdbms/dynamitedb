package dynamitedb.contracts.storage;

/** Baris hasil baca beserta RID-nya. */
public record StoredRow(RecordId rid, Row row) {}
