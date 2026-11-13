package dynamitedb.sm.index;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.IndexKind;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.PreparedWrite;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.Value;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Membuat, mencari, dan memelihara index (SM-10, SM-11). */
public final class IndexManager {
  private final HeapFile heap;
  private final RecordSerializer serializer;

  private final Map<String, EqualityIndex> indexes = new HashMap<>(); // Nama index -> index aktif
  private final Map<String, IndexMetadata> metadata = new HashMap<>(); // Nama index -> metadata
  private final Map<String, List<String>> indexesByTable =
      new HashMap<>(); // Nama tabel -> daftar nama index
  private final Map<String, Schema> schemas =
      new HashMap<>(); // Nama tabel -> schema terakhir yang diketahui

  /** Read heap via HeapFile (bukan RecordStore) untuk hindari constructor cycle. */
  public IndexManager(HeapFile heap, RecordSerializer serializer) {
    this.heap = Objects.requireNonNull(heap, "heap");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
  }

  public synchronized void registerSchema(String table, Schema schema) {
    Objects.requireNonNull(table, "table");
    schemas.put(table, Objects.requireNonNull(schema, "schema"));
  }

  public synchronized void createIndex(IndexMetadata meta) {
    Objects.requireNonNull(meta, "meta");
    if (indexes.containsKey(meta.name())) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Index sudah ada: " + meta.name());
    }
    EqualityIndex fresh =
        meta.kind() == IndexKind.B_PLUS_TREE ? new BPlusTreeIndex() : new HashIndex();
    backfill(meta, fresh);
    indexes.put(meta.name(), fresh);
    metadata.put(meta.name(), meta);
    indexesByTable.computeIfAbsent(meta.table(), t -> new ArrayList<>()).add(meta.name());
  }

  public synchronized List<RecordId> lookup(String index, IndexPredicate predicate) {
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(predicate, "predicate");
    EqualityIndex target = indexes.get(index);
    if (target == null) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Index tidak dikenal: " + index);
    }
    // perbandingan SQL dengan NULL bernilai UNKNOWN, jadi sama seperti scan: tidak ada baris
    if (predicate.value() instanceof NullValue) {
      return List.of();
    }
    switch (predicate.operator()) {
      case EQ:
        return target.lookup(predicate.value());
      case LT:
      case LTE:
      case GT:
      case GTE:
        if (target instanceof OrderedIndex ordered) {
          return ordered.range(predicate);
        }
        throw new DbmsException(
            ErrorCode.UNSUPPORTED_OPERATION,
            "Pencarian rentang tidak didukung index hash: " + index);
      default:
        throw new DbmsException(
            ErrorCode.UNSUPPORTED_OPERATION,
            "Operator tidak dilayani index: " + predicate.operator());
    }
  }

  /** True bila index sudah aktif. */
  public synchronized boolean hasIndex(String name) {
    return indexes.containsKey(Objects.requireNonNull(name, "name"));
  }

  /** Drop index, dipakai bila publish ke catalog gagal. */
  public synchronized void dropIndex(String name) {
    Objects.requireNonNull(name, "name");
    indexes.remove(name);
    IndexMetadata meta = metadata.remove(name);
    if (meta != null) {
      List<String> names = indexesByTable.get(meta.table());
      if (names != null) {
        names.remove(name);
      }
    }
  }

  /** Clear semua index dan schema, dipakai saat generation switch. */
  public synchronized void clear() {
    indexes.clear();
    metadata.clear();
    indexesByTable.clear();
    schemas.clear();
  }

  public synchronized void rebuildAll() {
    for (IndexMetadata meta : metadata.values()) {
      EqualityIndex fresh =
          meta.kind() == IndexKind.B_PLUS_TREE ? new BPlusTreeIndex() : new HashIndex();
      backfill(meta, fresh);
      indexes.put(meta.name(), fresh);
    }
  }

  public synchronized void onWrite(PreparedWrite write) {
    Objects.requireNonNull(write, "write");
    List<String> names = indexesByTable.get(write.table());
    if (names == null || names.isEmpty()) {
      return;
    }
    RecordId rid = Objects.requireNonNull(write.rid(), "write.rid()");
    for (String name : names) {
      EqualityIndex target = indexes.get(name);
      IndexMetadata meta = metadata.get(name);
      if (target == null || meta == null) {
        continue; // index belum diterbitkan
      }
      // keluarkan RID dari key lama
      RecordImage before = write.before();
      if (before != null && before.exists()) {
        target.remove(extractKey(meta, before), rid);
      }
      // daftarkan RID pada key baru
      RecordImage after = write.after();
      if (after != null && after.exists()) {
        target.insert(extractKey(meta, after), rid);
      }
    }
  }

  private void backfill(IndexMetadata meta, EqualityIndex target) {
    Schema schema = schemas.get(meta.table());
    if (schema == null) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Schema tabel belum terdaftar: " + meta.table());
    }
    int columnPos = columnPosition(schema, meta.column());
    for (RecordId rid : heap.recordIds(meta.table())) {
      RecordImage image = heap.read(rid);
      if (!image.exists()) {
        continue; // hanya baris hidup yang masuk index
      }
      Row row = serializer.decode(image, schema);
      target.insert(row.values().get(columnPos), rid);
    }
  }

  private Value extractKey(IndexMetadata meta, RecordImage image) {
    Schema schema = schemas.get(meta.table());
    if (schema == null) {
      throw new DbmsException(ErrorCode.SQL_BIND, "Schema tabel belum terdaftar: " + meta.table());
    }
    int columnPos = columnPosition(schema, meta.column());
    Row row = serializer.decode(image, schema);
    return row.values().get(columnPos);
  }

  private static int columnPosition(Schema schema, String column) {
    List<Column> columns = schema.columns();
    for (int i = 0; i < columns.size(); i++) {
      if (columns.get(i).name().equalsIgnoreCase(column)) {
        return i;
      }
    }
    throw new DbmsException(ErrorCode.SQL_BIND, "Column tidak ada: " + column);
  }
}
