package dynamitedb.sm.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.api.RowCursor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.DiskManager;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.heap.HeapFile;
import dynamitedb.sm.heap.RecordStore;
import dynamitedb.sm.index.IndexManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CHAR dengan karakter NUL di tengah tidak boleh tersimpan terpotong (issue #59). */
class CharNulTest {
  private static final TransactionId TX = new TransactionId(1);
  private static final Schema SCHEMA =
      new Schema(
          List.of(
              new Column("course", "course_id", DataType.INTEGER, 0, false),
              new Column("course", "code", DataType.CHAR, 6, false),
              new Column("course", "title", DataType.VARCHAR, 20, false)));
  private static final String WITH_NUL = "A" + (char) 0 + "B";

  @TempDir Path directory;

  private BufferPool buffer;
  private CatalogManager catalog;
  private RecordStore records;

  @Test
  void charWithEmbeddedNulIsRejectedInsteadOfTruncated() {
    var serializer = new RecordSerializer();
    var error =
        assertThrows(DbmsException.class, () -> serializer.encode(row(1, WITH_NUL, "x"), SCHEMA));
    assertEquals(ErrorCode.STORAGE_FAILURE, error.getCode());

    Row plain = row(2, "AB", "x");
    assertEquals(plain, serializer.decode(serializer.encode(plain, SCHEMA), SCHEMA));
  }

  @Test
  void varcharKeepsEmbeddedNul() {
    var serializer = new RecordSerializer();
    Row row = row(3, "AB", WITH_NUL);
    assertEquals(row, serializer.decode(serializer.encode(row, SCHEMA), SCHEMA));
  }

  @Test
  void rejectedInsertLeavesNoRowAfterRestart() {
    open();
    catalog.createTable("course", SCHEMA);
    records.apply(
        records.prepareInsert(TX, "course", row(9000, "OK", "kept")), new LogSequenceNumber(1));
    assertThrows(
        DbmsException.class,
        () -> records.prepareInsert(TX, "course", row(9010, WITH_NUL, "NUL row")));
    buffer.flushDirtyThrough(new LogSequenceNumber(Long.MAX_VALUE));

    open();
    assertEquals(List.of(row(9000, "OK", "kept")), scan());
    assertEquals(1, catalog.getStatistics("course").rowCount());
  }

  private void open() {
    buffer = new BufferPool(new DiskManager(directory), l -> {}, 4);
    catalog = new CatalogManager(directory);
    var serializer = new RecordSerializer();
    var heap = new HeapFile(directory, buffer, serializer, catalog);
    records =
        new RecordStore(heap, buffer, serializer, new IndexManager(heap, serializer), catalog);
  }

  private List<Row> scan() {
    var rows = new ArrayList<Row>();
    try (RowCursor cursor = records.openScan("course")) {
      for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
        rows.add(next.get().row());
      }
    }
    return rows;
  }

  private static Row row(int id, String code, String title) {
    return new Row(List.of(new IntValue(id), new TextValue(code), new TextValue(title)));
  }
}
