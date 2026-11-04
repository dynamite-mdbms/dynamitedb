package dynamitedb.contracts.storage;

import java.util.Map;

// draf dari QP biar bisa compile, nanti ganti punya SM
public record TableStatistics(
    long rowCount, long blockCount, int averageRowBytes, Map<String, Long> distinctCounts) {

  public TableStatistics {
    distinctCounts = Map.copyOf(distinctCounts);
  }
}
