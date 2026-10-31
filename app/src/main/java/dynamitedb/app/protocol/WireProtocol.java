package dynamitedb.app.protocol;

import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Version 1: length-prefixed UTF-8 and tagged values, never Java object deserialization. */
public final class WireProtocol {
  public static final int MAGIC = 0x44594E41;
  public static final int VERSION = 1;
  public static final int EXECUTE = 1;
  public static final int CLOSE = 2;
  public static final int OK = 0;
  public static final int ERROR = 1;
  public static final int MAX_SQL_BYTES = 1_048_576;
  private static final int MAX_STRING_BYTES = 32 * 1_048_576;

  private WireProtocol() {}

  public static void writeString(DataOutputStream out, String value) throws IOException {
    if (value == null) {
      throw new IOException("A protocol string must not be null");
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_STRING_BYTES) {
      throw new IOException("Protocol string is too large");
    }
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  public static String readString(DataInputStream in) throws IOException {
    return readString(in, MAX_STRING_BYTES);
  }

  public static String readString(DataInputStream in, int limit) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > limit) {
      throw new IOException("Invalid protocol string length");
    }
    byte[] bytes = new byte[size];
    in.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  public static void writeResult(DataOutputStream out, QueryResult result) throws IOException {
    List<Column> columns = result.schema().columns();
    out.writeInt(columns.size());
    for (Column column : columns) {
      writeString(out, column.relation());
      writeString(out, column.name());
      writeString(out, column.dataType().name());
      out.writeInt(column.maxLength());
      out.writeBoolean(column.nullable());
    }
    out.writeInt(result.rows().size());
    for (Row row : result.rows()) {
      if (row.values().size() != columns.size()) {
        throw new IOException("Result row does not match its schema");
      }
      for (Value value : row.values()) {
        switch (value) {
          case NullValue ignored -> out.writeByte(0);
          case IntValue integer -> {
            out.writeByte(1);
            out.writeInt(integer.value());
          }
          case FloatValue number -> {
            out.writeByte(2);
            out.writeDouble(number.value());
          }
          case TextValue text -> {
            out.writeByte(3);
            writeString(out, text.value());
          }
          case BooleanValue bool -> {
            out.writeByte(4);
            out.writeBoolean(bool.value());
          }
        }
      }
    }
    out.writeLong(result.affectedRows());
    writeString(out, result.message() == null ? "" : result.message());
  }

  public static QueryResult readResult(DataInputStream in) throws IOException {
    int columnCount = count(in.readInt(), "columns");
    List<Column> columns = new ArrayList<>();
    for (int index = 0; index < columnCount; index++) {
      String relation = readString(in);
      String name = readString(in);
      DataType type;
      try {
        type = DataType.valueOf(readString(in));
      } catch (IllegalArgumentException failure) {
        throw new IOException("Unknown column type", failure);
      }
      columns.add(new Column(relation, name, type, in.readInt(), in.readBoolean()));
    }
    int rowCount = count(in.readInt(), "rows");
    List<Row> rows = new ArrayList<>();
    for (int index = 0; index < rowCount; index++) {
      List<Value> values = new ArrayList<>();
      for (int column = 0; column < columnCount; column++) {
        values.add(
            switch (in.readUnsignedByte()) {
              case 0 -> NullValue.INSTANCE;
              case 1 -> new IntValue(in.readInt());
              case 2 -> new FloatValue(in.readDouble());
              case 3 -> new TextValue(readString(in));
              case 4 -> new BooleanValue(in.readBoolean());
              default -> throw new IOException("Unknown value tag");
            });
      }
      rows.add(new Row(List.copyOf(values)));
    }
    return new QueryResult(
        new Schema(List.copyOf(columns)), List.copyOf(rows), in.readLong(), readString(in));
  }

  private static int count(int value, String field) throws IOException {
    if (value < 0) {
      throw new IOException("Invalid " + field + " count");
    }
    return value;
  }
}
