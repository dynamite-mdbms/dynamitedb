package dynamitedb.contracts;

import java.util.Objects;

public record RecoveryMutation(RecordId rid, RecordImage targetImage) {
  public RecoveryMutation {
    Objects.requireNonNull(rid, "rid");
    Objects.requireNonNull(targetImage, "targetImage");
  }
}
