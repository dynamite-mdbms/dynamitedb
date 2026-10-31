package dynamitedb.sm.draft;

/** Temporary contract: CHAR/VARCHAR scalar; the column supplies the text length. */
public record TextValue(String value) implements Value {}
