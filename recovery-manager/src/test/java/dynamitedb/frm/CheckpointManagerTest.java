package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dynamitedb.contracts.CheckpointResult;
import dynamitedb.contracts.CheckpointService;
import dynamitedb.contracts.LogSequenceNumber;
import dynamitedb.frm.mock.MockRecoveryEnvironment;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CheckpointManagerTest {
  @Test
  void checkpointReturnsMockResultThroughServiceContract() {
    CheckpointService service = MockRecoveryEnvironment.create().checkpoint();

    CheckpointResult result = service.checkpoint();

    assertEquals(new LogSequenceNumber(1), result.checkpointLsn());
  }

  @Test
  void periodicMethodsAreCallable() {
    CheckpointManager manager = MockRecoveryEnvironment.create().checkpoint();

    assertDoesNotThrow(
        () -> {
          manager.startPeriodic(Duration.ofSeconds(1));
          manager.stopPeriodic();
        });
  }
}
