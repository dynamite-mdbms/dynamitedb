package dynamitedb.contracts.storage;

/** Hasil ekspresi boolean, bukan tipe kolom yang disimpan. */
public record BooleanValue(boolean value) implements Value {}
