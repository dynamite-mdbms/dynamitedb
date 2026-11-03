package dynamitedb.ccm.deadlock;

import dynamitedb.contracts.transaction.TransactionId;
import java.util.Map;

public record WaitForGraph(Map<TransactionId, TransactionCycle> edges) {}
