package dynamitedb.contracts.storage;

// draf dari QP biar bisa compile, nanti ganti punya SM
public record RecordId(long tableId, long pageNumber, int slotNumber, long generation) {}
