package dynamitedb.contracts.storage;

public record RecordId(long tableId, long pageNumber, int slotNumber, long generation) {}
