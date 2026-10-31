package dynamitedb.sm.draft;

/** Temporary scalar family from the architecture's shared value definitions. */
public sealed interface Value permits IntValue, FloatValue, TextValue, BooleanValue, NullValue {}
