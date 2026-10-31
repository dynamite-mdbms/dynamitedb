package dynamitedb.contracts;

/** Rollback port. Real rollback completes undo and termination before CCM releases protection. */
public interface RollbackService {
  void rollbackCompletely(TransactionId tx);
}
