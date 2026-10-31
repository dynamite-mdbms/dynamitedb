package dynamitedb.recovery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dynamitedb.contracts.recovery.CheckpointResult;
import dynamitedb.contracts.recovery.CheckpointService;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CheckpointManagerTest {
  @Test
  void checkpointReturnsMockResultThroughServiceContract() {
    CheckpointService service = new CheckpointManager();

    CheckpointResult result = service.checkpoint();

    assertEquals(new LogSequenceNumber(1), result.checkpointLsn());
  }

  @Test
  void periodicMethodsAreCallable() {
    CheckpointManager manager = new CheckpointManager();

    assertDoesNotThrow(
        () -> {
          manager.startPeriodic(Duration.ofSeconds(1));
          manager.stopPeriodic();
        });
  }
}
