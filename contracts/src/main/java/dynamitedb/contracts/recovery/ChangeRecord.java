package dynamitedb.contracts.recovery;

import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.Objects;

public record ChangeRecord(
    TransactionId tx, String table, RecordId rid, RecordImage before, RecordImage after) {
  public ChangeRecord {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(rid, "rid");
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(after, "after");
    if (table.isBlank() || (!before.exists() && !after.exists())) {
      throw new IllegalArgumentException(
          "A change needs a table and an existing before or after image");
    }
  }
}
