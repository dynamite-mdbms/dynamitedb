package dynamitedb.contracts;

public record RecordId(long tableId, long pageNumber, int slotNumber, long generation) {}
