package dynamitedb.contracts.storage;

/** Image baris terserialisasi, exists false berarti baris tidak ada. */
public record RecordImage(boolean exists, ImmutableBytes encoded) {}
