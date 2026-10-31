package dynamitedb.sm.draft;

/** Temporary schema column shape; replace with the agreed shared Column contract. */
public record Column(
    String relation, String name, DataType dataType, int maxLength, boolean nullable) {}
