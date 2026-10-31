package dynamitedb.contracts.transaction;

// draf dari QP biar bisa compile, nanti ganti punya CCM
public record AccessRequest(TransactionId tx, ResourceId resource, AccessMode mode) {}
