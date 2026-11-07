package dynamitedb.qp.operator;

import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Helper pembuat data uji: tabel = schema + baris, dibungkus jadi ListOperator
final class TestRows {

  private TestRows() {}

  // Membuat schema dari nama kolom, semuanya milik relasi yang sama.
  static Schema schema(String relation, String... columnNames) {
    List<Column> cols = new ArrayList<>();
    for (String name : columnNames) {
      // tipe kolom tidak dipakai Join/Sort, jadi cukup diisi VARCHAR untuk semua kolom
      cols.add(new Column(relation, name, DataType.VARCHAR, 255, true));
    }

    return new Schema(cols);
  }

  // Integer -> IntValue, Double -> FloatValue, String -> TextValue, null -> NullValue.
  static OperatorRow row(Schema schema, Object... vals) {
    List<Value> values = new ArrayList<>();
    for (Object v : vals) {
      values.add(toValue(v));
    }

    return new OperatorRow(new Row(values), schema, Map.of());
  }

  static ListOperator table(Schema schema, Object[]... rows) {
    List<OperatorRow> out = new ArrayList<>();
    for (Object[] r : rows) {
      out.add(row(schema, r));
    }

    return new ListOperator(out);
  }

  static Object[] r(Object... vals) {
    return vals;
  }

  static ColumnRef col(String relation, String name) {
    return new ColumnRef(Optional.of(relation), name);
  }

  static ColumnRef col(String name) {
    return new ColumnRef(Optional.empty(), name);
  }

  // Buka operator, ambil semua, tutup.
  static List<OperatorRow> drain(Operator op) {
    List<OperatorRow> out = new ArrayList<>();
    op.open();
    for (Optional<OperatorRow> r = op.next(); r.isPresent(); r = op.next()) {
      out.add(r.get());
    }

    op.close();
    return out;
  }

  // Ambil nilai Integer/Double/String/null dari kolom ke-i tiap baris
  static List<Object> column(List<OperatorRow> rows, int i) {
    List<Object> out = new ArrayList<>();
    for (OperatorRow r : rows) {
      out.add(raw(r.row().values().get(i)));
    }

    return out;
  }

  static List<Object> list(Object... vals) {
    return Arrays.asList(vals);
  }

  private static Value toValue(Object v) {
    if (v == null) {
      return NullValue.INSTANCE;
    }
    if (v instanceof Integer i) {
      return new IntValue(i);
    }
    if (v instanceof Double d) {
      return new FloatValue(d);
    }
    if (v instanceof String s) {
      return new TextValue(s);
    }

    throw new IllegalArgumentException("Tipe tidak didukung di test: " + v);
  }

  private static Object raw(Value v) {
    if (v instanceof NullValue) {
      return null;
    }
    if (v instanceof IntValue i) {
      return i.value();
    }
    if (v instanceof FloatValue f) {
      return f.value();
    }
    if (v instanceof TextValue t) {
      return t.value();
    }

    return v;
  }
}
