package dynamitedb.qo.join;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.BinaryOp;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.IndexMetadata;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TableStatistics;
import dynamitedb.contracts.storage.Value;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class Qo5TestSupport {

  private Qo5TestSupport() {}

  static ColumnRef column(String relation, String name) {
    return new ColumnRef(Optional.of(relation), name);
  }

  static ColumnRef column(String name) {
    return new ColumnRef(Optional.empty(), name);
  }

  static BinaryExpression compare(Expression left, BinaryOp operator, Expression right) {
    return new BinaryExpression(left, operator, right);
  }

  static ColumnExpression columnExpression(String relation, String name) {
    return new ColumnExpression(column(relation, name));
  }

  static LiteralExpression literal(Value value) {
    return new LiteralExpression(value);
  }

  static TableScanPlan scan(String table) {
    return new TableScanPlan(table, table);
  }

  static TableScanPlan scan(String table, String alias) {
    return new TableScanPlan(table, alias);
  }

  static CatalogAccess catalog(
      Map<String, TableStatistics> statistics, List<IndexMetadata> indexes) {
    return new TestCatalog(statistics, indexes, Map.of());
  }

  /**
   * Like {@link #catalog(Map, List)}, but every table in {@code columns} has those INTEGER columns.
   */
  static CatalogAccess catalog(
      Map<String, TableStatistics> statistics, Map<String, List<String>> columns) {
    return new TestCatalog(statistics, List.of(), columns);
  }

  private static final class TestCatalog implements CatalogAccess {

    private final Map<String, TableStatistics> statistics;
    private final List<IndexMetadata> indexes;
    private final Map<String, List<String>> columns;

    private TestCatalog(
        Map<String, TableStatistics> statistics,
        List<IndexMetadata> indexes,
        Map<String, List<String>> columns) {
      this.statistics = Map.copyOf(statistics);
      this.indexes = List.copyOf(indexes);
      this.columns = Map.copyOf(columns);
    }

    @Override
    public List<String> listTables() {
      return List.copyOf(statistics.keySet());
    }

    @Override
    public long getTableId(String table) {
      return statistics.keySet().stream().sorted().toList().indexOf(table) + 1L;
    }

    @Override
    public Schema getSchema(String table) {
      return new Schema(
          columns.getOrDefault(table, List.of("id")).stream()
              .map(name -> new Column(table, name, DataType.INTEGER, 4, false))
              .toList());
    }

    @Override
    public TableStatistics getStatistics(String table) {
      return statistics.get(table);
    }

    @Override
    public List<IndexMetadata> listIndexes(String table) {
      return indexes.stream().filter(index -> index.table().equals(table)).toList();
    }
  }
}
