package dynamitedb.contracts.recovery;

import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;

/** Perubahan dari FRM ke SM tanpa membawa format log internal FRM. */
public record RecoveryMutation(RecordId rid, RecordImage targetImage) {}
