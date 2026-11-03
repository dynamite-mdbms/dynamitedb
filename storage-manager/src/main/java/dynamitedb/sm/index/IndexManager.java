package dynamitedb.sm.index;

import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Membuat, mencari, dan memelihara index (SM-10, SM-11). */
public final class IndexManager {
  private final Map<String, EqualityIndex> indexes = new HashMap<>();

  public void createIndex(IndexMetadata meta) {
    // TODO: bangun index dari heap, terbitkan setelah lengkap dan durable
    indexes.put(
        meta.name(), meta.kind() == IndexKind.B_PLUS_TREE ? new BPlusTreeIndex() : new HashIndex());
  }

  public List<RecordId> lookup(String index, IndexPredicate predicate) {
    // TODO: EQ ke strategi mana pun, rentang hanya ke OrderedIndex
    return List.of();
  }

  public void rebuildAll() {
    // TODO: bangun ulang semua index dari heap hasil recovery
  }

  public void onWrite(PreparedWrite write) {
    // TODO: perbarui entry index sesuai before dan after image
  }
}
