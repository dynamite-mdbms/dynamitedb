package dynamitedb.ccm.recovery;

import dynamitedb.ccm.lifecycle.TransactionRegistry;
import dynamitedb.contracts.api.MaintenanceGuard;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import java.time.Duration;
import java.util.Objects;

public class MaintenanceController {
  private final TransactionRegistry registry;
  private boolean leased;
  private Thread leaseOwner;
  private boolean recoveryPending = true;
  private int admissionBlocks;

  public MaintenanceController(TransactionRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
    registry.holdAdmission();
  }

  public MaintenanceGuard acquire() {
    boolean acquired = false;
    try {
      synchronized (this) {
        if (leased && leaseOwner == Thread.currentThread()) {
          throw new DbmsException(ErrorCode.INVALID_STATE, "Maintenance sudah dipegang thread ini");
        }
        while (leased) {
          wait();
        }
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedException();
        }
        blockAdmission();
        leased = true;
        leaseOwner = Thread.currentThread();
        acquired = true;
      }
      while (!registry.awaitNoActive(Duration.ofDays(1))) {}
      return new MaintenanceLease(this);
    } catch (InterruptedException failure) {
      if (acquired) {
        releaseLease();
      }
      Thread.currentThread().interrupt();
      throw new DbmsException(
          ErrorCode.RECOVERY_FAILURE, "Menunggu maintenance terinterupsi", failure);
    }
  }

  public synchronized void blockAdmission() {
    registry.holdAdmission();
    admissionBlocks++;
  }

  public synchronized void resumeAdmission() {
    if (admissionBlocks == 0) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Tidak ada admission block untuk dilepas");
    }
    registry.releaseAdmission();
    admissionBlocks--;
  }

  public synchronized void initializeRecoveredState(Runnable initializer) {
    Objects.requireNonNull(initializer, "initializer");
    if ((leased && leaseOwner != Thread.currentThread()) || (!recoveryPending && !leased)) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE, "Inisialisasi recovery memerlukan maintenance eksklusif");
    }
    if (!recoveryPending) {
      beginRecovery();
    }
    initializer.run();
    recoveryPending = false;
    registry.releaseAdmission();
  }

  public synchronized void beginRecovery() {
    if (!leased || leaseOwner != Thread.currentThread()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Recovery memerlukan maintenance eksklusif");
    }
    if (!recoveryPending) {
      registry.holdAdmission();
      recoveryPending = true;
    }
  }

  synchronized void releaseLease() {
    resumeAdmission();
    leased = false;
    leaseOwner = null;
    notifyAll();
  }
}
