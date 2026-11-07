package dynamitedb.sm.index;

import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Index hash untuk pencarian kesamaan (SM-10, SM-11). */
public final class HashIndex implements EqualityIndex {

  private final Map<Value, List<RecordId>> buckets = new HashMap<>();

  @Override
  public synchronized List<RecordId> lookup(Value key) {
    Objects.requireNonNull(key, "key");
    List<RecordId> bucket = buckets.get(key);
    return bucket == null ? List.of() : List.copyOf(bucket); // Kembalikan immutable copy
  }

  @Override
  public synchronized void insert(Value key, RecordId rid) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(rid, "rid");
    List<RecordId> bucket = buckets.computeIfAbsent(key, k -> new java.util.ArrayList<>()); // key duplikat
    if (!bucket.contains(rid)) {
      bucket.add(rid);
    }
  }

  @Override
  public synchronized void remove(Value key, RecordId rid) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(rid, "rid");
    List<RecordId> bucket = buckets.get(key);
    if (bucket == null) {
      return; // key tidak dikenal
    }
    bucket.remove(rid);
    if (bucket.isEmpty()) {
      buckets.remove(key);
    }
  }
}
