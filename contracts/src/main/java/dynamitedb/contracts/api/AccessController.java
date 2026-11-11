package dynamitedb.contracts.api;

import dynamitedb.contracts.transaction.AccessDecision;
import dynamitedb.contracts.transaction.AccessRequest;
import dynamitedb.contracts.transaction.WaitToken;

/**
 * Contract for requesting and awaiting transaction access; only {@code GRANTED} permits operations.
 * This interface does not define a locking implementation.
 */
public interface AccessController {
  /** Requests access; a {@code WAITING} result is not permission to proceed. */
  AccessDecision requestAccess(AccessRequest request);

  /**
   * Awaits the request identified by its transaction-bound ticket; providers validate unknown,
   * foreign, or stale tickets.
   */
  AccessDecision awaitAccess(WaitToken ticket);
}
