package dynamitedb.ccm.timestamp;

import dynamitedb.contracts.transaction.TransactionId;
import java.util.List;

public record VerificationResult(boolean serializable, List<TransactionId> serialOrder) {}
