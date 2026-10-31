package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.CheckpointResult;

/** Checkpoint port. Real success requires WAL force, page flush and durable completion. */
public interface CheckpointService {
  CheckpointResult checkpoint();
}
