package dynamitedb.sm.draft;

/** Temporary contract: Recovery payload crossing into SM without FRM log internals. */
public record RecoveryMutation(RecordId rid, RecordImage targetImage) {}
