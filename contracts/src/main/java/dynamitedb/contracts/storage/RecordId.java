package dynamitedb.contracts.storage;

/** Alamat fisik baris beserta inkarnasi slotnya. */
public record RecordId(long tableId, long pageNumber, int slotNumber, long generation) {}
