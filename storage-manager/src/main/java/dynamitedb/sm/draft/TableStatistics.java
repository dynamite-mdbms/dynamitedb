package dynamitedb.sm.draft;

import java.util.Map;

/** Temporary immutable statistics shape from the architecture's shared type definitions. */
public record TableStatistics(
    long rowCount, long blockCount, int averageRowBytes, Map<String, Long> distinctCounts) {
  public TableStatistics {
    distinctCounts = Map.copyOf(distinctCounts);
  }
}
