package dynamitedb.sm.draft;

/** Temporary contract: Physical row identity including slot incarnation. */
public record RecordId(long tableId, long pageNumber, int slotNumber, long generation) {}
