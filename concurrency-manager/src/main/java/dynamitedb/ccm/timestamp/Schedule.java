package dynamitedb.ccm.timestamp;

import java.util.List;

public record Schedule(List<ScheduledOperation> operations) {}
