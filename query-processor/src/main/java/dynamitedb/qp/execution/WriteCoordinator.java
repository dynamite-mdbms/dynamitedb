package dynamitedb.qp.execution;

import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.PreparedWrite;
import java.util.Objects;

/** Mencatat perubahan ke WAL lewat CCM dulu, baru menerapkannya di SM. */
public class WriteCoordinator {

  private final WriteStore storage;
  private final ChangeLogger logger;

  public WriteCoordinator(WriteStore storage, ChangeLogger logger) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  // gagal setelah tercatat di log tidak di-cancel; pemanggil wajib abort lewat CCM
  public void execute(PreparedWrite prepared) {
    LogSequenceNumber lsn;
    try {
      lsn = logger.recordChange(prepared.tx(), prepared);
    } catch (RuntimeException failure) {
      cancelAfterFailure(prepared, failure);
      throw failure;
    }
    if (lsn == null) {
      DbmsException failure =
          new DbmsException(ErrorCode.WAL_FAILURE, "CCM did not return an LSN for the change");
      cancelAfterFailure(prepared, failure);
      throw failure;
    }
    storage.applyWrite(prepared, lsn);
  }

  public void cancel(PreparedWrite prepared) {
    storage.cancelPrepared(prepared);
  }

  private void cancelAfterFailure(PreparedWrite prepared, RuntimeException failure) {
    try {
      storage.cancelPrepared(prepared);
    } catch (RuntimeException cancelFailure) {
      failure.addSuppressed(cancelFailure);
    }
  }
}
