package dynamitedb.ccm.timestamp;

import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.ResourceId;
import dynamitedb.contracts.transaction.TransactionId;

public record ScheduledOperation(TransactionId tx, ResourceId resource, AccessMode mode) {}
