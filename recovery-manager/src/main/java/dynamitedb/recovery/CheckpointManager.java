package dynamitedb.recovery;

import java.time.Duration;

import dynamitedb.contracts.recovery.CheckpointResult;
import dynamitedb.contracts.recovery.CheckpointService;
import dynamitedb.contracts.recovery.LogSequenceNumber;

/** the Checkpoint Manager contract. It does not persist a checkpoint. */
public final class CheckpointManager implements CheckpointService {
  @Override
  public CheckpointResult checkpoint() {
    return new CheckpointResult(new LogSequenceNumber(1));
  }

  public void startPeriodic(Duration interval) {}

  public void stopPeriodic() {}
}
