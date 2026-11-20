package dynamitedb.qp.execution;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.AccessMode;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.qp.expression.ExpressionEvaluator;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Statement-wide validation under CCM authorization, before preparing any tuple write. */
final class KeyConstraints {
  private final CatalogAccess catalog;
  private final PlanTranslator translator;
  private final TransactionCoordinator coordinator;

  KeyConstraints(
      CatalogAccess catalog, PlanTranslator translator, TransactionCoordinator coordinator) {
    this.catalog = catalog;
    this.translator = translator;
    this.coordinator = coordinator;
  }

  Statement insert(String table, TransactionId tx) {
    return new Statement(
        table, tx, primaryColumns(table), catalog.listForeignKeys(table), List.of());
  }

  Statement update(String table, String column, TransactionId tx) {
    List<String> primary = primaryColumns(table);
    boolean changesPrimary = primary.stream().anyMatch(column::equalsIgnoreCase);
    return new Statement(
        table,
        tx,
        primary,
        catalog.listForeignKeys(table),
        changesPrimary ? catalog.listReferencingForeignKeys(table) : List.of());
  }

  Statement delete(String table, TransactionId tx) {
    return new Statement(
        table, tx, List.of(), List.of(), catalog.listReferencingForeignKeys(table));
  }

  private List<String> primaryColumns(String table) {
    return catalog.getPrimaryKey(table).map(PrimaryKey::columns).orElse(List.of());
  }

  record Change(Row before, Row after) {}

  final class Statement {
    private final String table;
    private final TransactionId tx;
    private final List<String> primary;
    private final List<ForeignKey> outgoing;
    private final List<ForeignKey> incoming;

    private Statement(
        String table,
        TransactionId tx,
        List<String> primary,
        List<ForeignKey> outgoing,
        List<ForeignKey> incoming) {
      this.table = table;
      this.tx = tx;
      this.primary = primary;
      this.outgoing = outgoing;
      this.incoming = incoming;

      // Timestamp READ doesn't exclude a newer writer; hold WRITE intents through commit/abort.
      Set<String> tables = new TreeSet<>();
      tables.add(table);
      outgoing.forEach(key -> tables.add(key.referencedTable()));
      incoming.forEach(key -> tables.add(key.table()));
      tables.forEach(name -> coordinator.authorizeTable(tx, name, AccessMode.WRITE));
    }

    void validateInsert(Row row) {
      validate(Map.of(), List.of(row));
    }

    void validateChanges(Map<RecordId, Change> changes) {
      if (changes.isEmpty() || (primary.isEmpty() && outgoing.isEmpty() && incoming.isEmpty())) {
        return;
      }
      List<Row> after =
          changes.values().stream().map(Change::after).filter(row -> row != null).toList();
      validate(changes, after);
    }

    private void validate(Map<RecordId, Change> changes, Collection<Row> after) {
      if (!primary.isEmpty() && !after.isEmpty()) {
        Projection pk = projection(table, primary);
        Set<List<Value>> candidates = keySet();
        for (Row row : after) {
          List<Value> key = pk.newKey(row);
          if (hasNull(key)) throw violation("PRIMARY KEY of " + table + " does not accept NULL");
          if (!candidates.add(key)) throw violation("Duplicate PRIMARY KEY on " + table);
        }
        if (scan(
            table, (rid, row) -> !changes.containsKey(rid) && candidates.contains(pk.key(row)))) {
          throw violation("Duplicate PRIMARY KEY on " + table);
        }
      }

      for (ForeignKey fk : outgoing) {
        Projection child = projection(table, fk.columns());
        Set<List<Value>> missing = keySet();
        for (Row row : after) {
          List<Value> key = child.newKey(row);
          if (!hasNull(key)) missing.add(key); // MATCH SIMPLE
        }
        if (missing.isEmpty()) continue;
        String parentTable = fk.referencedTable();
        Projection parent = projection(parentTable, fk.referencedColumns());
        boolean self = table.equalsIgnoreCase(parentTable);
        if (self) after.forEach(row -> missing.remove(parent.key(row)));
        if (!missing.isEmpty()) {
          scan(
              parentTable,
              (rid, row) -> {
                if (!self || !changes.containsKey(rid)) missing.remove(parent.key(row));
                return missing.isEmpty();
              });
        }
        if (!missing.isEmpty()) {
          throw violation("FOREIGN KEY on " + table + " has no match in " + parentTable);
        }
      }

      for (ForeignKey fk : incoming) {
        Projection parent = projection(table, fk.referencedColumns());
        Set<List<Value>> removed = keySet();
        for (Change change : changes.values()) {
          List<Value> oldKey = parent.key(change.before());
          if (change.after() == null || compareKeys(oldKey, parent.key(change.after())) != 0) {
            removed.add(oldKey);
          }
        }
        if (removed.isEmpty()) continue;
        String childTable = fk.table();
        Projection child = projection(childTable, fk.columns());
        boolean self = table.equalsIgnoreCase(childTable);
        if ((self && after.stream().anyMatch(row -> references(child, row, removed)))
            || scan(
                childTable,
                (rid, row) ->
                    (!self || !changes.containsKey(rid)) && references(child, row, removed))) {
          throw violation("PRIMARY KEY on " + table + " is referenced by " + childTable);
        }
      }
    }

    private boolean scan(String name, RowMatch match) {
      var operator = translator.translate(new TableScanPlan(name, name), tx);
      try {
        operator.open();
        for (Optional<OperatorRow> next = operator.next();
            next.isPresent();
            next = operator.next()) {
          OperatorRow row = next.get();
          if (match.test(row.origins().get(name), row.row())) return true;
        }
        return false;
      } finally {
        operator.close();
      }
    }
  }

  private Projection projection(String table, List<String> names) {
    List<Column> columns = catalog.getSchema(table).columns();
    List<Integer> positions =
        names.stream()
            .map(
                name -> {
                  for (int i = 0; i < columns.size(); i++) {
                    if (columns.get(i).name().equalsIgnoreCase(name)) return i;
                  }
                  throw new DbmsException(
                      ErrorCode.INVALID_STATE, "Unknown key column " + table + "." + name);
                })
            .toList();
    return new Projection(columns, positions);
  }

  private record Projection(List<Column> columns, List<Integer> positions) {
    List<Value> key(Row row) {
      return positions.stream().map(row.values()::get).toList();
    }

    List<Value> newKey(Row row) {
      for (int position : positions) {
        if (row.values().get(position) instanceof TextValue value) {
          Column column = columns.get(position);
          String text = value.value();
          // SM uses UTF-8 for both types and zero-byte padding for CHAR.
          byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
          if (!text.equals(new String(encoded, StandardCharsets.UTF_8))
              || encoded.length > column.maxLength()
              || (column.dataType() == DataType.CHAR && text.indexOf('\0') >= 0)
              || (column.dataType() == DataType.VARCHAR && encoded.length > 0xFFFF)) {
            throw violation("Invalid stored text for key column " + column.name());
          }
        }
      }
      return key(row);
    }
  }

  private static boolean references(Projection child, Row row, Set<List<Value>> keys) {
    List<Value> key = child.key(row);
    return !hasNull(key) && keys.contains(key);
  }

  private static boolean hasNull(List<Value> key) {
    return key.contains(NullValue.INSTANCE);
  }

  private static Set<List<Value>> keySet() {
    // Use predicate equality, including trailing-space treatment and Double.compare's signed zero.
    return new TreeSet<>(KeyConstraints::compareKeys);
  }

  private static int compareKeys(List<Value> left, List<Value> right) {
    for (int i = 0; i < left.size(); i++) {
      int cmp = ExpressionEvaluator.compare(left.get(i), right.get(i));
      if (cmp != 0) return cmp;
    }
    return 0;
  }

  private static DbmsException violation(String message) {
    return new DbmsException(ErrorCode.SQL_BIND, message);
  }

  @FunctionalInterface
  private interface RowMatch {
    boolean test(RecordId rid, Row row);
  }
}
