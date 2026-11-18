package dynamitedb.qo.binding;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexStatement;
import dynamitedb.contracts.query.CreateTableStatement;
import dynamitedb.contracts.query.DeleteStatement;
import dynamitedb.contracts.query.DropTableStatement;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FromSource;
import dynamitedb.contracts.query.InSubqueryExpression;
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
import dynamitedb.contracts.storage.DropBehavior;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ForeignKey;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.PrimaryKey;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class Binder {

  private static final String NUMBER = "number";
  private static final String TEXT = "text";
  private static final String BOOLEAN = "boolean";
  private static final String NULL = "null";
  // Same limits as SM's CatalogManager, so DDL fails at bind time rather than in storage.
  private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,127}";
  private static final int MAX_COLUMNS = 1024;
  private static final int MAX_TEXT_LENGTH = 65535;

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
      case CreateTableStatement create -> bindCreateTable(create);
      case DropTableStatement drop -> bindDropTable(drop);
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
    Map<String, Column> merged = new HashMap<>();
    FromSource from = bindFrom(select.from(), new HashSet<>(), columns, merged);
    Schema scope = new Schema(columns);
    List<ColumnRef> projection =
        select.projection().stream()
            .flatMap(ref -> expand(rename(ref, merged), scope).stream())
            .toList();
    List<SortKey> orderBy =
        select.orderBy().stream()
            .map(
                key ->
                    new SortKey(
                        qualified(resolve(rename(key.column(), merged), scope)), key.direction()))
            .toList();
    return new SelectStatement(
        projection,
        from,
        select.predicate().map(p -> predicate(rename(p, merged), scope)),
        orderBy,
        select.limit());
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
    } else if (!kind(value, scope).equals(kindOf(target.dataType()))
        || (target.dataType() == DataType.INTEGER && isFloat(value, scope))) {
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

  /** PRIMARY KEY implies NOT NULL; names are matched case-insensitively like table lookups. */
  private CreateTableStatement bindCreateTable(CreateTableStatement create) {
    String table = identifier(create.table());
    if (catalog.listTables().stream().anyMatch(table::equalsIgnoreCase)) {
      throw error("Table already exists: " + table);
    }
    if (create.columns().isEmpty() || create.columns().size() > MAX_COLUMNS) {
      throw error("A table requires between 1 and " + MAX_COLUMNS + " columns");
    }
    Set<String> names = new HashSet<>();
    for (Column column : create.columns()) {
      if (!names.add(identifier(column.name()).toLowerCase(Locale.ROOT))) {
        throw error("Duplicate column: " + column.name());
      }
      boolean text = kindOf(column.dataType()).equals(TEXT);
      if (text && (column.maxLength() < 1 || column.maxLength() > MAX_TEXT_LENGTH)) {
        throw error(
            "Invalid length "
                + column.maxLength()
                + " for column "
                + column.name()
                + ", expected 1 to "
                + MAX_TEXT_LENGTH);
      }
    }
    if (create.primaryKeys().size() > 1) throw error("Multiple primary keys for table " + table);
    Schema scope = new Schema(create.columns());
    List<String> primaryKey =
        create.primaryKeys().stream()
            .findFirst()
            .map(key -> names(ownColumns(key.columns(), scope, "PRIMARY KEY")))
            .orElse(List.of());
    List<ForeignKey> foreignKeys =
        create.foreignKeys().stream()
            .map(key -> bindForeignKey(key, table, scope, primaryKey))
            .toList();
    List<Column> columns =
        create.columns().stream()
            .map(
                c ->
                    primaryKey.contains(c.name())
                        ? new Column(table, c.name(), c.dataType(), c.maxLength(), false)
                        : c)
            .toList();
    List<PrimaryKey> primaryKeys =
        primaryKey.isEmpty() ? List.of() : List.of(new PrimaryKey(primaryKey));
    return new CreateTableStatement(table, columns, primaryKeys, foreignKeys);
  }

  /** {@code primaryKey} is the bound key of the table being created, for self-references. */
  private ForeignKey bindForeignKey(
      ForeignKey key, String table, Schema scope, List<String> primaryKey) {
    List<Column> columns = ownColumns(key.columns(), scope, "FOREIGN KEY");
    boolean self = key.referencedTable().equalsIgnoreCase(table);
    String target = self ? table : tableName(key.referencedTable());
    Schema targetScope = self ? scope : tableScope(target, target);
    List<Column> referenced =
        key.referencedColumns().stream()
            .map(name -> resolve(new ColumnRef(Optional.empty(), name), targetScope))
            .toList();
    for (int i = 0; i < columns.size(); i++) {
      Column column = columns.get(i);
      Column other = referenced.get(i);
      if (other.dataType() != column.dataType()) {
        throw error(
            "FOREIGN KEY "
                + column.name()
                + " ("
                + column.dataType()
                + ") does not match "
                + target
                + "."
                + other.name()
                + " ("
                + other.dataType()
                + ")");
      }
    }
    // Same rule as SM: the referenced columns are exactly the target's PRIMARY KEY, in order.
    List<String> targetKey =
        self
            ? primaryKey
            : catalog.getPrimaryKey(target).map(PrimaryKey::columns).orElse(List.of());
    if (!names(referenced).equals(targetKey)) {
      throw error("FOREIGN KEY must reference the PRIMARY KEY of " + target);
    }
    return new ForeignKey(table, names(columns), target, names(referenced));
  }

  /** RESTRICT rejects a table other tables reference; a self-reference goes with the table. */
  private DropTableStatement bindDropTable(DropTableStatement drop) {
    String table = tableName(drop.table());
    if (drop.behavior() == DropBehavior.RESTRICT) {
      for (ForeignKey key : catalog.listReferencingForeignKeys(table)) {
        if (!key.table().equals(table)) {
          throw error(
              "Table "
                  + table
                  + " is referenced by a FOREIGN KEY on "
                  + key.table()
                  + "; use DROP TABLE ... CASCADE");
        }
      }
    }
    return new DropTableStatement(table, drop.behavior());
  }

  /** Resolves key columns of the table being created; like SM, a key may not repeat a column. */
  private static List<Column> ownColumns(List<String> names, Schema scope, String clause) {
    Set<String> seen = new HashSet<>();
    List<Column> columns = new ArrayList<>();
    for (String name : names) {
      Column column =
          scope.columns().stream()
              .filter(c -> c.name().equalsIgnoreCase(name))
              .findFirst()
              .orElseThrow(() -> error(clause + " column is not in the table: " + name));
      if (!seen.add(column.name())) throw error(clause + " repeats column " + column.name());
      columns.add(column);
    }
    return columns;
  }

  private static List<String> names(List<Column> columns) {
    return columns.stream().map(Column::name).toList();
  }

  private static String identifier(String name) {
    if (!name.matches(IDENTIFIER)) {
      throw error("Invalid identifier (ASCII letters, digits, '_', at most 128): " + name);
    }
    return name;
  }

  /**
   * Appends the visible columns of {@code source} to {@code scope}. A NATURAL JOIN drops the right
   * copy of each common column, as QP does, and records it in {@code merged} so references to it
   * resolve to the surviving left column.
   */
  private FromSource bindFrom(
      FromSource source, Set<String> aliases, List<Column> scope, Map<String, Column> merged) {
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
    FromSource left = bindFrom(join.left(), aliases, scope, merged);
    List<Column> leftColumns = List.copyOf(scope);
    List<Column> rightColumns = new ArrayList<>();
    FromSource right = bindFrom(join.right(), aliases, rightColumns, merged);
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
      } else {
        merged.put(key(column.relation(), column.name()), common);
      }
    }
    Schema joined = new Schema(scope);
    return new JoinSource(
        left, right, join.kind(), join.condition().map(c -> predicate(rename(c, merged), joined)));
  }

  private static Expression rename(Expression expression, Map<String, Column> merged) {
    return switch (expression) {
      case LiteralExpression literal -> literal;
      case ColumnExpression column -> new ColumnExpression(rename(column.column(), merged));
      case BinaryExpression binary ->
          new BinaryExpression(
              rename(binary.left(), merged), binary.operator(), rename(binary.right(), merged));
      case InSubqueryExpression in ->
          new InSubqueryExpression(
              rename(in.operand(), merged), in.query(), in.plan(), in.negated());
    };
  }

  private static ColumnRef rename(ColumnRef ref, Map<String, Column> merged) {
    return ref.relation()
        .map(relation -> merged.get(key(relation, ref.column())))
        .map(Binder::qualified)
        .orElse(ref);
  }

  private static String key(String relation, String column) {
    return (relation + "." + column).toLowerCase(Locale.ROOT);
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
      case InSubqueryExpression in ->
          new InSubqueryExpression(
              qualify(in.operand(), scope), bindSubquery(in.query()), in.plan(), in.negated());
    };
  }

  private String kind(Expression expression, Schema scope) {
    return switch (expression) {
      case LiteralExpression literal -> kindOf(literal.value());
      case ColumnExpression column -> kindOf(resolve(column.column(), scope).dataType());
      case BinaryExpression binary -> binaryKind(binary, scope);
      case InSubqueryExpression in -> {
        String operand = kind(in.operand(), scope);
        String column = kindOf(outputSchema(in.query()).columns().get(0).dataType());
        if (operand.equals(BOOLEAN) || !fits(operand, column)) {
          throw error("IN cannot compare " + operand + " with a " + column + " subquery");
        }
        yield BOOLEAN;
      }
    };
  }

  /** Subquery di-bind dengan scope sendiri; kolom query luar tidak terlihat (tanpa korelasi). */
  private SelectStatement bindSubquery(SelectStatement query) {
    SelectStatement bound;
    try {
      bound = bindSelect(query);
    } catch (DbmsException e) {
      if (!e.getMessage().startsWith("Unknown column")) throw e;
      throw error(e.getMessage() + " (correlated subqueries are not supported)");
    }
    if (bound.projection().size() != 1) {
      throw error("Subquery must select exactly one column, got " + bound.projection().size());
    }
    return bound;
  }

  private String binaryKind(BinaryExpression binary, Schema scope) {
    String left = kind(binary.left(), scope);
    String right = kind(binary.right(), scope);
    boolean valid =
        switch (binary.operator()) {
          case ADD, SUB, MUL, DIV -> fits(left, NUMBER) && fits(right, NUMBER);
          case AND -> fits(left, BOOLEAN) && fits(right, BOOLEAN);
          case IS, IS_NOT -> right.equals(NULL);
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

  // Mirrors QP arithmetic: INTEGER op INTEGER stays INTEGER, any FLOAT operand yields FLOAT.
  private boolean isFloat(Expression expression, Schema scope) {
    return switch (expression) {
      case LiteralExpression literal -> literal.value() instanceof FloatValue;
      case ColumnExpression column -> resolve(column.column(), scope).dataType() == DataType.FLOAT;
      case BinaryExpression binary ->
          isFloat(binary.left(), scope) || isFloat(binary.right(), scope);
      case InSubqueryExpression in -> false;
    };
  }

  private static void checkValue(Column column, Value value) {
    if (value instanceof NullValue && !column.nullable()) {
      throw error("Column " + column.name() + " does not accept NULL");
    }
    boolean fits =
        switch (value) {
          case NullValue n -> column.nullable();
          case IntValue i -> kindOf(column.dataType()).equals(NUMBER);
          case FloatValue f -> column.dataType() == DataType.FLOAT;
          // Storage limits CHAR/VARCHAR by UTF-8 bytes, not Java chars.
          case TextValue t ->
              kindOf(column.dataType()).equals(TEXT)
                  && t.value().getBytes(StandardCharsets.UTF_8).length <= column.maxLength();
          case BooleanValue b -> false;
        };
    if (!fits) {
      throw error(
          "Value does not fit column "
              + column.relation()
              + "."
              + column.name()
              + " ("
              + column.dataType()
              + ")");
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
