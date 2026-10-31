package dynamitedb.ccm.timestamp;

import dynamitedb.contracts.transaction.ResourceId;

public class TimestampRegistry {
  public long readTs(ResourceId resource) {
    return 0;
  }

  public long writeTs(ResourceId resource) {
    return 0;
  }

  public void markRead(ResourceId resource, long timestamp) {}

  public void markWrite(ResourceId resource, long timestamp) {}
}
