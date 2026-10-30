package dynamitedb.contracts.storage;

// draf dari QP biar bisa compile, nanti ganti punya SM
public record Column(String name, DataType type, int length) {}
