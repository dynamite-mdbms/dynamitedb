package dynamitedb.contracts.recovery;

import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import java.util.Objects;

/** Perubahan dari FRM ke SM tanpa membawa format log internal FRM. */
public record RecoveryMutation(RecordId rid, RecordImage targetImage) {
  public RecoveryMutation {
    Objects.requireNonNull(rid, "rid");
    Objects.requireNonNull(targetImage, "targetImage");
  }
}
