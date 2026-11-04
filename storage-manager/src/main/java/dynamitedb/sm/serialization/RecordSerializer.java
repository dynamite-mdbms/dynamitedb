package dynamitedb.sm.serialization;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Konversi baris ke image dan sebaliknya untuk keempat tipe data (SM-02, SM-03). */
public final class RecordSerializer {
  public static final byte FORMAT_VERSION = 1;

  public RecordSerializer() {}

  /**
   * Encodes a row into binary (RecordImage).
   *
   * @param row row to encode
   * @param schema table schema
   * @return encoded record image with exists = true
   * @throws DbmsException if row values do not conform to schema
   */
  public RecordImage encode(Row row, Schema schema) {
    if (row == null || schema == null) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Row and schema must not be null");
    }

    List<Column> columns = schema.columns();
    List<Value> values = row.values();
    if (values.size() != columns.size()) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Row column count ("
              + values.size()
              + ") does not match schema ("
              + columns.size()
              + ")");
    }

    int colCount = columns.size();
    int nullBitmapBytes = (colCount + 7) / 8;
    byte[] nullBitmap = new byte[nullBitmapBytes];
    ByteArrayOutputStream payload = new ByteArrayOutputStream();

    for (int i = 0; i < colCount; i++) {
      Column column = columns.get(i);
      Value value = values.get(i);
      if (value == null) {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE,
            "Column '" + column.name() + "' value must not be java's null; use NullValue.INSTANCE");
      }

      if (value instanceof NullValue) {
        if (!column.nullable()) {
          throw new DbmsException(
              ErrorCode.STORAGE_FAILURE, "Column '" + column.name() + "' is not nullable");
        }
        nullBitmap[i / 8] |= (byte) (1 << (i % 8));
        continue;
      }

      switch (column.dataType()) {
        case INTEGER -> {
          if (!(value instanceof IntValue intVal)) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Type mismatch for column '"
                    + column.name()
                    + "': expected INTEGER, got "
                    + value.getClass().getSimpleName());
          }
          ByteBuffer buf = ByteBuffer.allocate(4);
          buf.putInt(intVal.value());
          payload.writeBytes(buf.array());
        }
        case FLOAT -> {
          if (!(value instanceof FloatValue floatVal)) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Type mismatch for column '"
                    + column.name()
                    + "': expected FLOAT, got "
                    + value.getClass().getSimpleName());
          }
          ByteBuffer buf = ByteBuffer.allocate(8);
          buf.putDouble(floatVal.value());
          payload.writeBytes(buf.array());
        }
        case CHAR -> {
          if (!(value instanceof TextValue textVal)) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Type mismatch for column '"
                    + column.name()
                    + "': expected CHAR, got "
                    + value.getClass().getSimpleName());
          }
          String s = textVal.value();
          if (s == null) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE, "TextValue for column '" + column.name() + "' is null");
          }
          byte[] textBytes = s.getBytes(StandardCharsets.UTF_8);
          int maxLen = column.maxLength();
          if (maxLen < 0) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Invalid CHAR maxLength for column '" + column.name() + "': " + maxLen);
          }
          if (textBytes.length > maxLen) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Text length ("
                    + textBytes.length
                    + " bytes) exceeds CHAR("
                    + maxLen
                    + ") on column '"
                    + column.name()
                    + "'");
          }
          byte[] padded = new byte[maxLen];
          System.arraycopy(textBytes, 0, padded, 0, textBytes.length);
          payload.writeBytes(padded);
        }
        case VARCHAR -> {
          if (!(value instanceof TextValue textVal)) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Type mismatch for column '"
                    + column.name()
                    + "': expected VARCHAR, got "
                    + value.getClass().getSimpleName());
          }
          String s = textVal.value();
          if (s == null) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE, "TextValue for column '" + column.name() + "' is null");
          }
          byte[] textBytes = s.getBytes(StandardCharsets.UTF_8);
          ByteBuffer buf = ByteBuffer.allocate(Short.BYTES + textBytes.length);
          buf.putShort((short) textBytes.length);
          buf.put(textBytes);
          payload.writeBytes(buf.array());
        }
      }
    }

    byte[] payloadBytes = payload.toByteArray();
    ByteBuffer fullImage = ByteBuffer.allocate(1 + nullBitmapBytes + payloadBytes.length);
    fullImage.put(FORMAT_VERSION);
    fullImage.put(nullBitmap);
    fullImage.put(payloadBytes);

    return new RecordImage(true, ImmutableBytes.copyOf(fullImage.array()));
  }

  /**
   * Decodes binary (RecordImage) into a row.
   *
   * @param image binary record image
   * @param schema table schema
   * @return decoded relational row
   * @throws DbmsException if image is absent, corrupt, or truncated
   */
  public Row decode(RecordImage image, Schema schema) {
    if (image == null || schema == null) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Image and schema must not be null");
    }

    if (!image.exists()) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Cannot decode absent RecordImage");
    }

    byte[] bytes = image.encoded().copy();
    if (bytes.length < 1) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Record image data is empty or truncated");
    }

    int version = bytes[0];
    if (version != FORMAT_VERSION) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Unsupported record format version: " + version + ", expected: " + FORMAT_VERSION);
    }

    List<Column> columns = schema.columns();
    int colCount = columns.size();
    int nullBitmapBytes = (colCount + 7) / 8;
    if (1 + nullBitmapBytes > bytes.length) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Record image truncated: missing null bitmap");
    }

    ByteBuffer buf = ByteBuffer.wrap(bytes);
    buf.position(1);
    byte[] nullBitmap = new byte[nullBitmapBytes];
    buf.get(nullBitmap);

    List<Value> values = new ArrayList<>(colCount);
    for (int i = 0; i < colCount; i++) {
      Column column = columns.get(i);
      boolean isNull = (nullBitmap[i / 8] & (1 << (i % 8))) != 0;

      if (isNull) {
        if (!column.nullable()) {
          throw new DbmsException(
              ErrorCode.STORAGE_FAILURE,
              "Non-nullable column '" + column.name() + "' is marked null in record image");
        }
        values.add(NullValue.INSTANCE);
        continue;
      }

      switch (column.dataType()) {
        case INTEGER -> {
          if (buf.remaining() < 4) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Truncated INTEGER value on column '" + column.name() + "'");
          }
          values.add(new IntValue(buf.getInt()));
        }
        case FLOAT -> {
          if (buf.remaining() < 8) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Truncated FLOAT value on column '" + column.name() + "'");
          }
          values.add(new FloatValue(buf.getDouble()));
        }
        case CHAR -> {
          int len = column.maxLength();
          if (len < 0 || buf.remaining() < len) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Truncated CHAR value on column '" + column.name() + "'");
          }
          byte[] charBytes = new byte[len];
          buf.get(charBytes);
          int actualLen = len;
          for (int j = 0; j < len; j++) {
            if (charBytes[j] == 0) {
              actualLen = j;
              break;
            }
          }
          String s = new String(charBytes, 0, actualLen, StandardCharsets.UTF_8);
          values.add(new TextValue(s));
        }
        case VARCHAR -> {
          if (buf.remaining() < 2) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Truncated VARCHAR length prefix on column '" + column.name() + "'");
          }
          int varLen = Short.toUnsignedInt(buf.getShort());
          if (buf.remaining() < varLen) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "truncated VARCHAR value on column '" + column.name() + "'");
          }
          byte[] varcharBytes = new byte[varLen];
          buf.get(varcharBytes);
          String s = new String(varcharBytes, StandardCharsets.UTF_8);
          values.add(new TextValue(s));
        }
      }
    }

    if (buf.hasRemaining()) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Corrupted record image: " + buf.remaining() + " unread trailing bytes");
    }

    return new Row(values);
  }
}
