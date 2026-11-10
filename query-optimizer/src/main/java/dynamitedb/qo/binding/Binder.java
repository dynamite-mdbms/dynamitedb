package dynamitedb.qo.binding;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexStatement;
import dynamitedb.contracts.query.DeleteStatement;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FromSource;
import dynamitedb.contracts.query.InsertStatement;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableSource;
import dynamitedb.contracts.query.TransactionStatement;
import dynamitedb.contracts.query.UpdateStatement;
import dynamitedb.contracts.storage.BooleanValue;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class Binder {

  private static final String NUMBER = "number";
  private static final String TEXT = "text";
  private static final String BOOLEAN = "boolean";
  private static final String NULL = "null";

  private final CatalogAccess catalog;

  public Binder(CatalogAccess catalog) {
    this.catalog = catalog;
  }

  public Statement bind(Statement statement) {
    return switch (statement) {
      case SelectStatement select -> bindSelect(select);
      case InsertStatement insert -> bindInsert(insert);
      case UpdateStatement update -> bindUpdate(update);
      case DeleteStatement delete -> bindDelete(delete);
      case CreateIndexStatement create -> bindCreateIndex(create);
      case TransactionStatement transaction -> transaction;
    };
  }

  public Schema outputSchema(Statement bound) {
    if (!(bound instanceof SelectStatement select)) return new Schema(List.of());
    Schema scope = sourceScope(select.from());
    return new Schema(select.projection().stream().map(ref -> resolve(ref, scope)).toList());
  }

  public Column resolve(ColumnRef column, Schema scope) {
    List<Column> matches =
        scope.columns().stream()
            .filter(c -> c.name().equalsIgnoreCase(column.column()) && inRelation(column, c))
            .toList();
    if (matches.isEmpty()) throw error("Unknown column: " + label(column));
    if (matches.size() > 1) throw error("Ambiguous column: " + label(column));
    return matches.get(0);
  }

  private SelectStatement bindSelect(SelectStatement select) {
    List<Column> columns = new ArrayList<>();
    FromSource from = bindFrom(select.from(), new HashSet<>(), columns);
    Schema scope = new Schema(columns);
    List<ColumnRef> projection =
        select.projection().stream().flatMap(ref -> expand(ref, scope).stream()).toList();
    List<SortKey> orderBy =
        select.orderBy().stream()
            .map(key -> new SortKey(qualified(resolve(key.column(), scope)), key.direction()))
            .toList();
    return new SelectStatement(
        projection, from, select.predicate().map(p -> predicate(p, scope)), orderBy);
  }

  private InsertStatement bindInsert(InsertStatement insert) {
    String table = tableName(insert.table());
    List<Column> columns = tableScope(table, table).columns();
    if (insert.values().size() != columns.size()) {
      throw error(
          "INSERT into "
              + table
              + " expects "
              + columns.size()
              + " values, got "
              + insert.values().size());
    }
    for (int i = 0; i < columns.size(); i++) {
      if (insert.values().get(i) instanceof LiteralExpression literal) {
        checkValue(columns.get(i), literal.value());
      }
    }
    return new InsertStatement(table, insert.values());
  }

  private UpdateStatement bindUpdate(UpdateStatement update) {
    String table = tableName(update.table());
    Schema scope = tableScope(table, table);
    Column target = resolve(update.target(), scope);
    Expression value = qualify(update.expression(), scope);
    if (value instanceof LiteralExpression literal) {
      checkValue(target, literal.value());
    } else if (!kind(value, scope).equals(kindOf(target.dataType()))) {
      throw error("SET value does not match the type of " + target.name());
    }
    return new UpdateStatement(
        table, qualified(target), value, update.predicate().map(p -> predicate(p, scope)));
  }

  private DeleteStatement bindDelete(DeleteStatement delete) {
    String table = tableName(delete.table());
    Schema scope = tableScope(table, table);
    return new DeleteStatement(table, delete.predicate().map(p -> predicate(p, scope)));
  }

  private CreateIndexStatement bindCreateIndex(CreateIndexStatement create) {
    String table = tableName(create.table());
    Column column =
        resolve(new ColumnRef(Optional.empty(), create.column()), tableScope(table, table));
    boolean taken =
        catalog.listTables().stream()
            .flatMap(t -> catalog.listIndexes(t).stream())
            .anyMatch(index -> index.name().equalsIgnoreCase(create.index()));
    if (taken) throw error("Index already exists: " + create.index());
    return new CreateIndexStatement(create.index(), table, column.name(), create.kind());
  }

  private FromSource bindFrom(FromSource source, Set<String> aliases, List<Column> scope) {
    if (source instanceof TableSource table) {
      String name = tableName(table.table());
      String alias = table.alias().equals(table.table()) ? name : table.alias();
      if (!aliases.add(alias.toLowerCase(Locale.ROOT))) {
        throw error("Duplicate table alias: " + alias);
      }
      scope.addAll(tableScope(name, alias).columns());
      return new TableSource(name, alias);
    }
    JoinSource join = (JoinSource) source;
    FromSource left = bindFrom(join.left(), aliases, scope);
    List<Column> leftColumns = List.copyOf(scope);
    List<Column> rightColumns = new ArrayList<>();
    FromSource right = bindFrom(join.right(), aliases, rightColumns);
    for (Column column : rightColumns) {
      Column common =
          join.kind() == JoinKind.NATURAL
              ? leftColumns.stream()
                  .filter(c -> c.name().equalsIgnoreCase(column.name()))
                  .findFirst()
                  .orElse(null)
              : null;
      if (common == null) {
        scope.add(column);
      } else if (!kindOf(common.dataType()).equals(kindOf(column.dataType()))) {
        throw error("NATURAL JOIN column " + column.name() + " has incompatible types");
      }
    }
    Schema joined = new Schema(scope);
    return new JoinSource(
        left, right, join.kind(), join.condition().map(c -> predicate(c, joined)));
  }

  private String tableName(String table) {
    return catalog.listTables().stream()
        .filter(t -> t.equalsIgnoreCase(table))
        .findFirst()
        .orElseThrow(() -> error("Unknown table: " + table));
  }

  private Schema tableScope(String table, String alias) {
    return new Schema(
        catalog.getSchema(table).columns().stream()
            .map(c -> new Column(alias, c.name(), c.dataType(), c.maxLength(), c.nullable()))
            .toList());
  }

  private Schema sourceScope(FromSource source) {
    if (source instanceof TableSource table) return tableScope(table.table(), table.alias());
    JoinSource join = (JoinSource) source;
    List<Column> columns = new ArrayList<>(sourceScope(join.left()).columns());
    columns.addAll(sourceScope(join.right()).columns());
    return new Schema(columns);
  }

  private List<ColumnRef> expand(ColumnRef ref, Schema scope) {
    if (!ref.column().equals("*")) return List.of(qualified(resolve(ref, scope)));
    List<ColumnRef> columns =
        scope.columns().stream().filter(c -> inRelation(ref, c)).map(Binder::qualified).toList();
    if (columns.isEmpty()) throw error("Unknown table alias: " + label(ref));
    return columns;
  }

  private Expression predicate(Expression expression, Schema scope) {
    Expression bound = qualify(expression, scope);
    if (!kind(bound, scope).equals(BOOLEAN)) throw error("Condition must be boolean");
    return bound;
  }

  private Expression qualify(Expression expression, Schema scope) {
    return switch (expression) {
      case LiteralExpression literal -> literal;
      case ColumnExpression column ->
          new ColumnExpression(qualified(resolve(column.column(), scope)));
      case BinaryExpression binary ->
          new BinaryExpression(
              qualify(binary.left(), scope), binary.operator(), qualify(binary.right(), scope));
    };
  }

  private String kind(Expression expression, Schema scope) {
    return switch (expression) {
      case LiteralExpression literal -> kindOf(literal.value());
      case ColumnExpression column -> kindOf(resolve(column.column(), scope).dataType());
      case BinaryExpression binary -> binaryKind(binary, scope);
    };
  }

  private String binaryKind(BinaryExpression binary, Schema scope) {
    String left = kind(binary.left(), scope);
    String right = kind(binary.right(), scope);
    boolean valid =
        switch (binary.operator()) {
          case ADD, SUB, MUL, DIV -> fits(left, NUMBER) && fits(right, NUMBER);
          case AND -> fits(left, BOOLEAN) && fits(right, BOOLEAN);
          case EQ, NEQ, LT, LTE, GT, GTE ->
              !left.equals(BOOLEAN)
                  && !right.equals(BOOLEAN)
                  && (left.equals(NULL) || fits(right, left));
        };
    if (!valid) {
      throw error("Operator " + binary.operator() + " cannot combine " + left + " and " + right);
    }
    return switch (binary.operator()) {
      case ADD, SUB, MUL, DIV -> NUMBER;
      default -> BOOLEAN;
    };
  }

  private static void checkValue(Column column, Value value) {
    boolean fits =
        switch (value) {
          case NullValue n -> column.nullable();
          case IntValue i -> kindOf(column.dataType()).equals(NUMBER);
          case FloatValue f -> column.dataType() == DataType.FLOAT;
          case TextValue t ->
              kindOf(column.dataType()).equals(TEXT) && t.value().length() <= column.maxLength();
          case BooleanValue b -> false;
        };
    if (!fits) {
      throw error("Value does not fit column " + column.name() + " (" + column.dataType() + ")");
    }
  }

  private static String kindOf(Value value) {
    return switch (value) {
      case IntValue i -> NUMBER;
      case FloatValue f -> NUMBER;
      case TextValue t -> TEXT;
      case BooleanValue b -> BOOLEAN;
      case NullValue n -> NULL;
    };
  }

  private static String kindOf(DataType type) {
    return type == DataType.INTEGER || type == DataType.FLOAT ? NUMBER : TEXT;
  }

  private static boolean fits(String actual, String expected) {
    return actual.equals(expected) || actual.equals(NULL);
  }

  private static boolean inRelation(ColumnRef ref, Column column) {
    return ref.relation().map(column.relation()::equalsIgnoreCase).orElse(true);
  }

  private static ColumnRef qualified(Column column) {
    return new ColumnRef(Optional.of(column.relation()), column.name());
  }

  private static String label(ColumnRef ref) {
    return ref.relation().map(r -> r + ".").orElse("") + ref.column();
  }

  private static DbmsException error(String message) {
    return new DbmsException(ErrorCode.SQL_BIND, message);
  }
}
