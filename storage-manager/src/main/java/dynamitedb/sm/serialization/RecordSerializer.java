package dynamitedb.sm.serialization;

import dynamitedb.sm.draft.RecordImage;
import dynamitedb.sm.draft.Row;
import dynamitedb.sm.draft.Schema;

/** SM3 / Zeki Amani: canonical row/image encoding and physical format agreement (SM-02-SM-03). */
public final class RecordSerializer {
  public RecordImage encode(Row row, Schema schema) {
    // TODO(SM3): Define type encoding, text lengths, null bitmap, byte order and format version.
    // Validate schema order, value types, CHAR/VARCHAR lengths and column nullability.
    // Share this exact representation with runtime writes, WAL, recovery and index maintenance.
    throw new UnsupportedOperationException("TODO(SM3): encode a row image");
  }

  public Row decode(RecordImage image, Schema schema) {
    // TODO(SM3): Validate the same rules as encode and reject corrupt/truncated/incompatible data.
    // An absent image is not an existing row containing NullValue.INSTANCE.
    throw new UnsupportedOperationException("TODO(SM3): decode a row image");
  }
}
