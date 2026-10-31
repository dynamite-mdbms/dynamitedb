package dynamitedb.contracts.storage;

/** Definisi kolom, maxLength dipakai untuk CHAR dan VARCHAR. */
public record Column(
    String relation, String name, DataType dataType, int maxLength, boolean nullable) {}
