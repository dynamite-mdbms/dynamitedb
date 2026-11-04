package dynamitedb.contracts.transaction;

import dynamitedb.contracts.storage.RecordId;
import java.util.Objects;
import java.util.Optional;

/** Identifies a table or a record resource; record table identity must match its record ID. */
public record ResourceId(ResourceKind kind, long tableId, Optional<RecordId> recordId) {
  public ResourceId {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(recordId, "recordId");
    if (kind == ResourceKind.TABLE && recordId.isPresent()) {
      throw new IllegalArgumentException("Resource TABLE tidak boleh memiliki RecordId");
    }
    if (kind == ResourceKind.RECORD && recordId.isEmpty()) {
      throw new IllegalArgumentException("Resource RECORD wajib memiliki RecordId");
    }
    if (kind == ResourceKind.RECORD && tableId != recordId.orElseThrow().tableId()) {
      throw new IllegalArgumentException("Resource RECORD tableId must match RecordId tableId");
    }
  }

  public static ResourceId table(long tableId) {
    return new ResourceId(ResourceKind.TABLE, tableId, Optional.empty());
  }

  public static ResourceId record(RecordId rid) {
    return new ResourceId(ResourceKind.RECORD, rid.tableId(), Optional.of(rid));
  }
}
