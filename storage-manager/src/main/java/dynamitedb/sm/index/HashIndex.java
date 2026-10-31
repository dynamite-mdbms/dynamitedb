package dynamitedb.sm.index;

import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Value;
import java.util.List;

/** Index hash untuk pencarian kesamaan (SM-10, SM-11). */
public final class HashIndex implements EqualityIndex {
  @Override
  public List<RecordId> lookup(Value key) {
    // TODO: cari RID dengan key yang sama
    return List.of();
  }

  @Override
  public void insert(Value key, RecordId rid) {
    // TODO: dukung key duplikat
  }

  @Override
  public void remove(Value key, RecordId rid) {
    // TODO: hapus hanya RID ini, baris lain dengan key sama tetap ada
  }
}
