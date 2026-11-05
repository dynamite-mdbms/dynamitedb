package dynamitedb.sm.serialization;

import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.Value;
import java.util.Collections;

/** Konversi baris ke image dan sebaliknya untuk keempat tipe data (SM-02, SM-03). */
public final class RecordSerializer {
  public RecordImage encode(Row row, Schema schema) {
    // TODO: format byte, panjang teks, null bitmap, dan versi format
    // format yang sama dipakai WAL, recovery, dan index
    return new RecordImage(true, ImmutableBytes.copyOf(new byte[0]));
  }

  public Row decode(RecordImage image, Schema schema) {
    // TODO: validasi sama seperti encode, tolak data rusak atau terpotong
    return new Row(Collections.<Value>nCopies(schema.columns().size(), NullValue.INSTANCE));
  }
}
