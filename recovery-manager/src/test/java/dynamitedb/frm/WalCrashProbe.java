package dynamitedb.frm;

import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryHighWater;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.transaction.TransactionId;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/** Child process for a WAL test that exits without closing its writer. */
final class WalCrashProbe {
  private WalCrashProbe() {}

  public static void main(String[] args) {
    var seed =
        new RecoveryHighWater(new LogSequenceNumber(0), new TransactionId(0), 0, Optional.empty());
    var tx = new TransactionId(1);
    var image = new RecordImage(true, ImmutableBytes.copyOf(new byte[] {42}));
    var log = LogManager.createNew(Path.of(args[0]), UUID.fromString(args[1]), seed);
    var wal = new WalService(new CommitClock(), log);
    wal.begin(tx, 1);
    wal.appendChange(
        new ChangeRecord(tx, "items", new RecordId(1, 5, 1, 1), RecordImage.absent(), image));
    if (args[2].equals("commit")) wal.commitDurably(tx);
    Runtime.getRuntime().halt(0);
  }
}
