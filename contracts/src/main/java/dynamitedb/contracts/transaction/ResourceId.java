package dynamitedb.contracts.transaction;

import dynamitedb.contracts.storage.RecordId;
import java.util.Optional;

// draf dari QP biar bisa compile, nanti ganti punya CCM
public record ResourceId(ResourceKind kind, long tableId, Optional<RecordId> recordId) {}
