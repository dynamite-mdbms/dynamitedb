package dynamitedb.ccm.timestamp;

import dynamitedb.contracts.transaction.ResourceId;
import java.util.HashMap;
import java.util.Map;

public class TimestampRegistry {
  private final Map<ResourceId, Long> readTimestamps = new HashMap<>();
  private final Map<ResourceId, Long> writeTimestamps = new HashMap<>();

  public long readTs(ResourceId resource) {
    return readTimestamps.getOrDefault(resource, 0L);
  }

  public long writeTs(ResourceId resource) {
    return writeTimestamps.getOrDefault(resource, 0L);
  }

  public void markRead(ResourceId resource, long timestamp) {
    readTimestamps.merge(resource, timestamp, Math::max);
  }

  public void markWrite(ResourceId resource, long timestamp) {
    writeTimestamps.put(resource, timestamp);
  }
}
