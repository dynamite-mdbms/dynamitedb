package dynamitedb.ccm.deadlock;

import dynamitedb.contracts.transaction.TransactionId;
import java.util.List;

public record TransactionCycle(List<TransactionId> txIds) {}
