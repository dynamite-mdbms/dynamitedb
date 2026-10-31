package dynamitedb.ccm.timestamp;

import dynamitedb.contracts.transaction.ResourceId;

public abstract class TimestampRegistry {
  public abstract long readTs(ResourceId resource);

  public abstract long writeTs(ResourceId resource);

  public abstract void markRead(ResourceId resource, long timestamp);

  public abstract void markWrite(ResourceId resource, long timestamp);
}
