package dynamitedb.contracts.transaction;

import java.util.Objects;

/** An access request tied to one transaction, resource, and permission mode. */
public record AccessRequest(TransactionId tx, ResourceId resource, AccessMode mode) {
  public AccessRequest {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(resource, "resource");
    Objects.requireNonNull(mode, "mode");
  }
}
