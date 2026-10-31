package dynamitedb.qp.execution;

import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.PreparedWrite;

/** Mencatat perubahan ke WAL lewat CCM dulu, baru menerapkannya di SM. */
public class WriteCoordinator {

  private final WriteStore storage;
  private final ChangeLogger logger;

  public WriteCoordinator(WriteStore storage, ChangeLogger logger) {
    this.storage = storage;
    this.logger = logger;
  }

  // gagal setelah tercatat di log tidak di-cancel; pemanggil wajib abort lewat CCM
  public void execute(PreparedWrite prepared) {
    LogSequenceNumber lsn;
    try {
      lsn = logger.recordChange(prepared.tx(), prepared);
    } catch (RuntimeException e) {
      storage.cancelPrepared(prepared);
      throw e;
    }
    storage.applyWrite(prepared, lsn);
  }

  public void cancel(PreparedWrite prepared) {
    storage.cancelPrepared(prepared);
  }
}
