package dynamitedb.contracts.storage;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record RecordId(long tableId, long pageNumber, int slotNumber, long generation) {}
