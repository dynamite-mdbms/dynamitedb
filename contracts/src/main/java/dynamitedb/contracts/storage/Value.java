package dynamitedb.contracts.storage;

/** Nilai skalar yang disimpan di baris. */
public sealed interface Value permits IntValue, FloatValue, TextValue, BooleanValue, NullValue {}
