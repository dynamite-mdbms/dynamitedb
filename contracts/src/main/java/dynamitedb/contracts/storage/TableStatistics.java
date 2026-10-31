package dynamitedb.contracts.storage;

import java.util.Map;

/** Statistik tabel untuk estimasi biaya (SM-09). */
public record TableStatistics(
    long rowCount, long blockCount, int averageRowBytes, Map<String, Long> distinctCounts) {
  public TableStatistics {
    distinctCounts = Map.copyOf(distinctCounts);
  }
}
