package dynamitedb.sm.draft;

/** Temporary contract: Decoded row paired with physical identity. */
public record StoredRow(RecordId rid, Row row) {}
