package dynamitedb.contracts.storage;

// draf dari QP biar bisa compile, nanti ganti punya SM
// exists false untuk before INSERT dan after DELETE
public record RecordImage(boolean exists, ImmutableBytes encoded) {}
