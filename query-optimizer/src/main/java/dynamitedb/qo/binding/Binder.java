package dynamitedb.qo.binding;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.AggregateExpression;
import dynamitedb.contracts.query.AggregateFunction;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.CreateIndexStatement;
import dynamitedb.contracts.query.DeleteStatement;
import dynamitedb.contracts.query.ExistsSubqueryExpression;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FromSource;
import dynamitedb.contracts.query.InSubqueryExpression;
import dynamitedb.contracts.query.InsertStatement;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinSource;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.ScalarSubqueryExpression;
import dynamitedb.contracts.query.SelectItem;
import dynamitedb.contracts.query.SelectStatement;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.query.TableSource;
import dynamitedb.contracts.query.TransactionStatement;
import dynamitedb.contracts.query.UnaryExpression;
import dynamitedb.contracts.query.UpdateAssignment;
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
    List<Column> output = new ArrayList<>();
    for (int index = 0; index < select.items().size(); index++) {
      SelectItem item = select.items().get(index);
      if (item.expression() instanceof ColumnExpression column
          && column.column().column().equals("*")) {
        output.addAll(
            scope.columns().stream().filter(c -> inRelation(column.column(), c)).toList());
      } else {
        Column source =
            item.expression() instanceof ColumnExpression column
                ? resolve(column.column(), scope)
                : null;
        String name =
            item.alias().orElse(source == null ? "__select_item_" + index : source.name());
        if (source != null) {
          output.add(
              new Column(
                  source.relation(),
                  name,
                  source.dataType(),
                  source.maxLength(),
                  source.nullable()));
        } else {
          String type = kind(item.expression(), scope);
          if (type.equals(BOOLEAN) || type.equals(TEXT) || type.equals(NULL)) {
            throw error("SELECT expression has no supported output type: " + type);
          }
          output.add(
              new Column(
                  "",
                  name,
                  isFloat(item.expression(), scope) ? DataType.FLOAT : DataType.INTEGER,
                  0,
                  true));
        }
      }
    }
    return new Schema(output);
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
    List<SelectItem> items = new ArrayList<>();
    Set<String> aliases = new HashSet<>();
    for (SelectItem item : select.items()) {
      if (item.alias().isPresent() && !aliases.add(item.alias().get().toLowerCase(Locale.ROOT))) {
        throw error("Duplicate output alias: " + item.alias().get());
      }
      if (item.expression() instanceof ColumnExpression column
          && column.column().column().equals("*")) {
        if (item.alias().isPresent()) throw error("A wildcard cannot have an output alias");
        items.addAll(
            expand(rename(column.column(), merged), scope).stream()
                .map(ref -> new SelectItem(new ColumnExpression(ref), Optional.empty()))
                .toList());
      } else {
        Expression expression = qualify(rename(item.expression(), merged), scope);
        kind(expression, scope);
        items.add(new SelectItem(expression, item.alias()));
      }
    }
    List<ColumnRef> groupBy =
        select.groupBy().stream()
            .map(ref -> qualified(resolve(rename(ref, merged), scope)))
            .toList();
    Set<ColumnRef> groupKeys = Set.copyOf(groupBy);
    Optional<Expression> having =
        select.having().map(expression -> qualify(rename(expression, merged), scope));
    if (select.predicate().filter(Binder::containsAggregate).isPresent()) {
      throw error("Aggregate functions are not allowed in WHERE");
    }
    boolean aggregateQuery =
        !groupBy.isEmpty()
            || items.stream().anyMatch(item -> containsAggregate(item.expression()))
            || having.filter(Binder::containsAggregate).isPresent();
    if (aggregateQuery) {
      items.forEach(item -> validateGrouped(item.expression(), groupKeys, false));
      having.ifPresent(expression -> validateGrouped(expression, groupKeys, false));
    }
    Map<String, SelectItem> byAlias = new HashMap<>();
    items.stream()
        .filter(item -> item.alias().isPresent())
        .forEach(item -> byAlias.put(item.alias().get().toLowerCase(Locale.ROOT), item));
    List<SortKey> orderBy =
        select.orderBy().stream().map(key -> bindSortKey(key, byAlias, merged, scope)).toList();
    return new SelectStatement(
        items,
        from,
        select.predicate().map(p -> predicate(rename(p, merged), scope)),
        groupBy,
        having.map(expression -> aggregatePredicate(expression, scope)),
        orderBy);
  }

  private SortKey bindSortKey(
      SortKey key, Map<String, SelectItem> aliases, Map<String, Column> merged, Schema scope) {
    if (key.column().relation().isEmpty()) {
      SelectItem item = aliases.get(key.column().column().toLowerCase(Locale.ROOT));
      if (item != null) {
        if (item.expression() instanceof ColumnExpression column) {
          return new SortKey(
              qualified(resolve(rename(column.column(), merged), scope)), key.direction());
        }
        return new SortKey(
            new ColumnRef(Optional.empty(), item.alias().orElseThrow()), key.direction());
      }
    }
    return new SortKey(qualified(resolve(rename(key.column(), merged), scope)), key.direction());
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
    Set<String> targets = new HashSet<>();
    List<UpdateAssignment> assignments =
        update.assignments().stream()
            .map(
                assignment -> {
                  if (containsAggregate(assignment.expression())) {
                    throw error("Aggregate functions are not allowed in UPDATE assignments");
                  }
                  Column target = resolve(assignment.target(), scope);
                  if (!targets.add(target.name().toLowerCase(Locale.ROOT))) {
                    throw error("Duplicate UPDATE target: " + target.name());
                  }
                  Expression value = qualify(assignment.expression(), scope);
                  if (value instanceof LiteralExpression literal) {
                    checkValue(target, literal.value());
                  } else if (!kind(value, scope).equals(kindOf(target.dataType()))
                      || (target.dataType() == DataType.INTEGER && isFloat(value, scope))) {
                    throw error("SET value does not match the type of " + target.name());
                  }
                  return new UpdateAssignment(qualified(target), value);
                })
            .toList();
    return new UpdateStatement(
        table, assignments, update.predicate().map(p -> predicate(p, scope)));
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
      case AggregateExpression aggregate ->
          new AggregateExpression(
              aggregate.function(), aggregate.argument().map(ref -> rename(ref, merged)));
      case UnaryExpression unary ->
          new UnaryExpression(unary.operator(), rename(unary.operand(), merged));
      case InSubqueryExpression in ->
          new InSubqueryExpression(
              rename(in.operand(), merged), in.query(), in.plan(), in.negated());
      case ExistsSubqueryExpression exists -> exists;
      case ScalarSubqueryExpression scalar -> scalar;
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
    if (containsAggregate(expression))
      throw error("Aggregate functions are not allowed in this condition");
    return aggregatePredicate(expression, scope);
  }

  private Expression aggregatePredicate(Expression expression, Schema scope) {
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
      case AggregateExpression aggregate -> {
        if (aggregate.argument().isEmpty()) yield aggregate;
        Column column = resolve(aggregate.argument().get(), scope);
        String argumentKind = kindOf(column.dataType());
        if ((aggregate.function() == AggregateFunction.SUM
                || aggregate.function() == AggregateFunction.AVG)
            && !argumentKind.equals(NUMBER)) {
          throw error(aggregate.function() + " requires a numeric column");
        }
        if ((aggregate.function() == AggregateFunction.MIN
                || aggregate.function() == AggregateFunction.MAX)
            && argumentKind.equals(BOOLEAN)) {
          throw error(aggregate.function() + " does not support boolean columns");
        }
        yield new AggregateExpression(aggregate.function(), Optional.of(qualified(column)));
      }
      case UnaryExpression unary ->
          new UnaryExpression(unary.operator(), qualify(unary.operand(), scope));
      case InSubqueryExpression in ->
          new InSubqueryExpression(
              qualify(in.operand(), scope), bindSubquery(in.query()), in.plan(), in.negated());
      case ExistsSubqueryExpression exists ->
          new ExistsSubqueryExpression(
              bindExistsSubquery(exists.query()), exists.plan(), exists.negated());
      case ScalarSubqueryExpression scalar -> {
        SelectStatement query = bindSubquery(scalar.query());
        yield new ScalarSubqueryExpression(query, scalar.plan());
      }
    };
  }

  private String kind(Expression expression, Schema scope) {
    return switch (expression) {
      case LiteralExpression literal -> kindOf(literal.value());
      case ColumnExpression column -> kindOf(resolve(column.column(), scope).dataType());
      case BinaryExpression binary -> binaryKind(binary, scope);
      case AggregateExpression aggregate -> aggregateKind(aggregate, scope);
      case UnaryExpression unary -> {
        if (!kind(unary.operand(), scope).equals(BOOLEAN)) {
          throw error("NOT needs a boolean operand");
        }
        yield BOOLEAN;
      }
      case InSubqueryExpression in -> {
        String operand = kind(in.operand(), scope);
        String column = kindOf(outputSchema(in.query()).columns().get(0).dataType());
        if (operand.equals(BOOLEAN) || !fits(operand, column)) {
          throw error("IN cannot compare " + operand + " with a " + column + " subquery");
        }
        yield BOOLEAN;
      }
      case ExistsSubqueryExpression exists -> {
        bindExistsSubquery(exists.query());
        yield BOOLEAN;
      }
      case ScalarSubqueryExpression scalar -> {
        SelectStatement query = bindSubquery(scalar.query());
        yield kindOf(outputSchema(query).columns().get(0).dataType());
      }
    };
  }

  private SelectStatement bindSubquery(SelectStatement query) {
    SelectStatement bound = bindExistsSubquery(query);
    if (bound.projection().size() != 1) {
      throw error("Subquery must select exactly one column, got " + bound.projection().size());
    }
    return bound;
  }

  private SelectStatement bindExistsSubquery(SelectStatement query) {
    try {
      return bindSelect(query);
    } catch (DbmsException e) {
      if (!e.getMessage().startsWith("Unknown column")) throw e;
      throw error(e.getMessage() + " (correlated subqueries are not supported)");
    }
  }

  private String binaryKind(BinaryExpression binary, Schema scope) {
    String left = kind(binary.left(), scope);
    String right = kind(binary.right(), scope);
    boolean valid =
        switch (binary.operator()) {
          case ADD, SUB, MUL, DIV -> fits(left, NUMBER) && fits(right, NUMBER);
          case AND, OR -> fits(left, BOOLEAN) && fits(right, BOOLEAN);
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

  private String aggregateKind(AggregateExpression aggregate, Schema scope) {
    if (aggregate.function() == AggregateFunction.COUNT) return NUMBER;
    ColumnRef ref = aggregate.argument().orElseThrow();
    String argument = kindOf(resolve(ref, scope).dataType());
    return aggregate.function() == AggregateFunction.AVG ? NUMBER : argument;
  }

  private boolean isFloat(Expression expression, Schema scope) {
    return switch (expression) {
      case LiteralExpression literal -> literal.value() instanceof FloatValue;
      case ColumnExpression column -> resolve(column.column(), scope).dataType() == DataType.FLOAT;
      case BinaryExpression binary ->
          isFloat(binary.left(), scope) || isFloat(binary.right(), scope);
      case AggregateExpression aggregate ->
          aggregate.function() == AggregateFunction.AVG
              || aggregate
                  .argument()
                  .map(ref -> resolve(ref, scope).dataType() == DataType.FLOAT)
                  .orElse(false);
      case UnaryExpression unary -> isFloat(unary.operand(), scope);
      case ExistsSubqueryExpression exists -> false;
      case ScalarSubqueryExpression scalar -> false;
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

  private static boolean containsAggregate(Expression expression) {
    return switch (expression) {
      case AggregateExpression aggregate -> true;
      case BinaryExpression binary ->
          containsAggregate(binary.left()) || containsAggregate(binary.right());
      case UnaryExpression unary -> containsAggregate(unary.operand());
      case InSubqueryExpression in -> containsAggregate(in.operand());
      case ExistsSubqueryExpression exists -> false;
      case ScalarSubqueryExpression scalar -> false;
      case LiteralExpression literal -> false;
      case ColumnExpression column -> false;
    };
  }

  private static void validateGrouped(
      Expression expression, Set<ColumnRef> groupKeys, boolean insideAggregate) {
    switch (expression) {
      case AggregateExpression aggregate -> {
        if (insideAggregate) throw error("Nested aggregate functions are not supported");
      }
      case ColumnExpression column -> {
        if (!insideAggregate && !groupKeys.contains(column.column())) {
          throw error("Column is not grouped or aggregated: " + label(column.column()));
        }
      }
      case BinaryExpression binary -> {
        validateGrouped(binary.left(), groupKeys, insideAggregate);
        validateGrouped(binary.right(), groupKeys, insideAggregate);
      }
      case UnaryExpression unary -> validateGrouped(unary.operand(), groupKeys, insideAggregate);
      case InSubqueryExpression in -> validateGrouped(in.operand(), groupKeys, insideAggregate);
      case ExistsSubqueryExpression exists -> {}
      case ScalarSubqueryExpression scalar -> {}
      case LiteralExpression literal -> {}
    }
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
