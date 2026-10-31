package dynamitedb.sm.draft;

import java.util.UUID;

/** Temporary contract: Handle to an isolated historical recovery workspace. */
public record RecoveryStage(UUID id) {}
