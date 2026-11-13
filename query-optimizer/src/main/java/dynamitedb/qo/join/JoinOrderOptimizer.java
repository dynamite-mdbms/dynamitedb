package dynamitedb.qo.join;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.BinaryExpression;
import dynamitedb.contracts.query.ColumnExpression;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.LiteralExpression;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortKey;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.qo.binding.CostEstimator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/** Membangkitkan kandidat urutan dan pengelompokan join (QO-12). */
public class JoinOrderOptimizer {

  private static final int MAX_LEAVES = 6;
  private static final int MAX_CANDIDATES = 64;

  private final CatalogAccess catalog;

  public JoinOrderOptimizer(CatalogAccess catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public List<PlanNode> enumerate(PlanNode plan) {
    Objects.requireNonNull(plan, "plan");
    return variants(plan, MAX_CANDIDATES).stream().map(Map.Entry::getKey).toList();
  }

  /**
   * Each candidate subtree, paired with the column references it moved. Swapping a NATURAL JOIN
   * makes the new left side emit the shared columns, so every reference above must follow them.
   */
  private List<Map.Entry<PlanNode, Map<ColumnRef, ColumnRef>>> variants(PlanNode plan, int limit) {
    return switch (plan) {
      case JoinPlan join when join.kind() == JoinKind.NATURAL -> naturalVariants(join, limit);
      case JoinPlan join when reorderable(join.kind()) ->
          joinVariants(join, limit).stream()
              .map(p -> Map.entry(p, Map.<ColumnRef, ColumnRef>of()))
              .toList();
      case ProjectPlan project ->
          map(
              project.child(),
              limit,
              (child, renamed) ->
                  new ProjectPlan(
                      child, project.columns().stream().map(c -> rename(c, renamed)).toList()));
      case SortPlan sort ->
          map(
              sort.child(),
              limit,
              (child, renamed) ->
                  new SortPlan(
                      child,
                      sort.keys().stream()
                          .map(k -> new SortKey(rename(k.column(), renamed), k.direction()))
                          .toList()));
      case FilterPlan filter ->
          map(
              filter.child(),
              limit,
              (child, renamed) -> new FilterPlan(child, rename(filter.predicate(), renamed)));
      // UPDATE and DELETE read a single table, so no NATURAL JOIN below them can rename a column.
      case UpdatePlan update ->
          map(
              update.source(),
              limit,
              (source, renamed) ->
                  new UpdatePlan(source, update.table(), update.target(), update.value()));
      case DeletePlan delete ->
          map(delete.source(), limit, (source, renamed) -> new DeletePlan(source, delete.table()));
      default -> List.of(Map.entry(plan, Map.of()));
    };
  }

  /**
   * Every child variant in both input orders. A NATURAL JOIN is kept whole (never flattened into a
   * Cartesian product with explicit conditions), because its equalities come from column names.
   */
  private List<Map.Entry<PlanNode, Map<ColumnRef, ColumnRef>>> naturalVariants(
      JoinPlan join, int limit) {
    boolean swappable = CostEstimator.scans(join).size() <= MAX_LEAVES;
    List<Map.Entry<PlanNode, Map<ColumnRef, ColumnRef>>> result = new ArrayList<>();
    for (var left : variants(join.left(), limit)) {
      for (var right : variants(join.right(), limit)) {
        Map<ColumnRef, ColumnRef> renamed = new HashMap<>(left.getValue());
        renamed.putAll(right.getValue());
        result.add(
            Map.entry(
                new JoinPlan(left.getKey(), right.getKey(), JoinKind.NATURAL, join.condition()),
                renamed));
        Optional<Map<ColumnRef, ColumnRef>> swap =
            swappable ? swapRenames(left.getKey(), right.getKey()) : Optional.empty();
        if (swap.isPresent() && result.size() < limit) {
          result.add(
              Map.entry(
                  new JoinPlan(right.getKey(), left.getKey(), JoinKind.NATURAL, join.condition()),
                  then(renamed, swap.get())));
        }
        if (result.size() >= limit) return result;
      }
    }
    return result;
  }

  /**
   * QP emits each shared column once, from the left input. After a swap it comes from the other
   * side, so the left reference maps to the right one; the inner join keeps them equal. Empty when
   * a shared name repeats on one side: QP would then match a different set of columns.
   */
  private Optional<Map<ColumnRef, ColumnRef>> swapRenames(PlanNode left, PlanNode right) {
    Map<String, List<ColumnRef>> rightByName = byName(outputs(right));
    Map<ColumnRef, ColumnRef> renamed = new HashMap<>();
    for (Map.Entry<String, List<ColumnRef>> shared : byName(outputs(left)).entrySet()) {
      List<ColumnRef> other = rightByName.get(shared.getKey());
      if (other == null) continue;
      if (shared.getValue().size() > 1 || other.size() > 1) return Optional.empty();
      renamed.put(shared.getValue().get(0), other.get(0));
    }
    return Optional.of(renamed);
  }

  /** The columns QP emits for {@code node}, in order. */
  private List<ColumnRef> outputs(PlanNode node) {
    return switch (node) {
      case TableScanPlan scan -> tableColumns(scan.table(), scan.alias());
      case IndexScanPlan scan -> tableColumns(scan.table(), scan.alias());
      case FilterPlan filter -> outputs(filter.child());
      case SortPlan sort -> outputs(sort.child());
      case ProjectPlan project -> project.columns();
      case JoinPlan join -> {
        List<ColumnRef> columns = new ArrayList<>(outputs(join.left()));
        Set<String> leftNames = byName(columns).keySet();
        for (ColumnRef column : outputs(join.right())) {
          if (join.kind() != JoinKind.NATURAL || !leftNames.contains(name(column))) {
            columns.add(column);
          }
        }
        yield columns;
      }
      default -> List.of();
    };
  }

  private List<ColumnRef> tableColumns(String table, String alias) {
    return catalog.getSchema(table).columns().stream()
        .map(c -> new ColumnRef(Optional.of(alias), c.name()))
        .toList();
  }

  // QP and Binder match NATURAL JOIN columns case-insensitively.
  private static Map<String, List<ColumnRef>> byName(List<ColumnRef> columns) {
    Map<String, List<ColumnRef>> byName = new LinkedHashMap<>();
    for (ColumnRef column : columns) {
      byName.computeIfAbsent(name(column), key -> new ArrayList<>()).add(column);
    }
    return byName;
  }

  private static String name(ColumnRef column) {
    return column.column().toLowerCase(Locale.ROOT);
  }

  /** Applies {@code first}, then {@code second}. */
  private static Map<ColumnRef, ColumnRef> then(
      Map<ColumnRef, ColumnRef> first, Map<ColumnRef, ColumnRef> second) {
    Map<ColumnRef, ColumnRef> composed = new HashMap<>(second);
    first.forEach((from, to) -> composed.put(from, second.getOrDefault(to, to)));
    return composed;
  }

  private static ColumnRef rename(ColumnRef column, Map<ColumnRef, ColumnRef> renamed) {
    return renamed.getOrDefault(column, column);
  }

  private static Expression rename(Expression expression, Map<ColumnRef, ColumnRef> renamed) {
    return switch (expression) {
      case ColumnExpression column -> new ColumnExpression(rename(column.column(), renamed));
      case BinaryExpression binary ->
          new BinaryExpression(
              rename(binary.left(), renamed), binary.operator(), rename(binary.right(), renamed));
      case LiteralExpression literal -> literal;
    };
  }

  private List<PlanNode> joinVariants(JoinPlan join, int limit) {
    Flat flat = flatten(join);
    List<PlanNode> candidates = new ArrayList<>();
    candidates.add(join);
    if (flat.leaves().size() < 2 || flat.leaves().size() > MAX_LEAVES) return candidates;

    Set<PlanNode> unique = new LinkedHashSet<>(candidates);
    int[] indexes = new int[flat.leaves().size()];
    for (int i = 0; i < indexes.length; i++) indexes[i] = i;
    for (int[] order : permutations(indexes)) {
      List<PlanNode> ordered = new ArrayList<>();
      for (int index : order) ordered.add(flat.leaves().get(index));
      for (PlanNode grouping : groupings(ordered, 0, ordered.size(), limit)) {
        PlanNode candidate = attachConditions(grouping, flat.conditions());
        if (unique.add(candidate)) {
          candidates.add(candidate);
          if (candidates.size() >= limit) return candidates;
        }
      }
    }
    return candidates;
  }

  /** Generates every ordered binary grouping for one table order, bounded before cross products. */
  private static List<PlanNode> groupings(List<PlanNode> leaves, int start, int end, int limit) {
    if (end - start == 1) return List.of(leaves.get(start));
    List<PlanNode> result = new ArrayList<>();
    for (int split = start + 1; split < end; split++) {
      List<PlanNode> left = groupings(leaves, start, split, limit);
      List<PlanNode> right = groupings(leaves, split, end, limit);
      for (PlanNode leftPlan : left) {
        for (PlanNode rightPlan : right) {
          result.add(new JoinPlan(leftPlan, rightPlan, JoinKind.CARTESIAN, Optional.empty()));
          if (result.size() >= limit) return result;
        }
      }
    }
    return result;
  }

  private static PlanNode attachConditions(PlanNode plan, List<Expression> conditions) {
    PlanNode result = plan;
    for (Expression condition : conditions) result = attachCondition(result, condition);
    return result;
  }

  /** Places a predicate at the lowest subtree that contains every referenced relation. */
  private static PlanNode attachCondition(PlanNode node, Expression condition) {
    Set<String> used = new LinkedHashSet<>();
    boolean qualified = true;
    for (ColumnRef column : PlanExpressions.columns(condition)) {
      if (column.relation().isEmpty()) qualified = false;
      else used.add(column.relation().get());
    }
    if (used.isEmpty()) qualified = false;

    if (node instanceof JoinPlan join) {
      if (qualified && relations(join.left()).containsAll(used)) {
        return new JoinPlan(
            attachCondition(join.left(), condition), join.right(), join.kind(), join.condition());
      }
      if (qualified && relations(join.right()).containsAll(used)) {
        return new JoinPlan(
            join.left(), attachCondition(join.right(), condition), join.kind(), join.condition());
      }
      return new JoinPlan(
          join.left(),
          join.right(),
          JoinKind.THETA,
          PlanExpressions.combine(join.condition(), condition));
    }
    return new FilterPlan(node, condition);
  }

  private static Flat flatten(PlanNode node) {
    if (!(node instanceof JoinPlan join) || !reorderable(join.kind())) {
      return new Flat(List.of(node), List.of());
    }
    Flat left = flatten(join.left());
    Flat right = flatten(join.right());
    List<PlanNode> leaves = new ArrayList<>(left.leaves());
    leaves.addAll(right.leaves());
    List<Expression> conditions = new ArrayList<>(left.conditions());
    conditions.addAll(right.conditions());
    join.condition()
        .ifPresent(condition -> conditions.addAll(PlanExpressions.conjuncts(condition)));
    return new Flat(leaves, conditions);
  }

  /** Rebuilds the parent over each child variant, passing the child's renames up. */
  private List<Map.Entry<PlanNode, Map<ColumnRef, ColumnRef>>> map(
      PlanNode plan, int limit, BiFunction<PlanNode, Map<ColumnRef, ColumnRef>, PlanNode> parent) {
    List<Map.Entry<PlanNode, Map<ColumnRef, ColumnRef>>> mapped = new ArrayList<>();
    for (var variant : variants(plan, limit)) {
      mapped.add(Map.entry(parent.apply(variant.getKey(), variant.getValue()), variant.getValue()));
    }
    return mapped;
  }

  private static Set<String> relations(PlanNode node) {
    Set<String> names = new LinkedHashSet<>();
    collectRelations(node, names);
    return names;
  }

  private static void collectRelations(PlanNode node, Set<String> names) {
    switch (node) {
      case TableScanPlan scan -> {
        add(names, scan.alias());
        add(names, scan.table());
      }
      case IndexScanPlan scan -> {
        add(names, scan.alias());
        add(names, scan.table());
      }
      case FilterPlan filter -> collectRelations(filter.child(), names);
      case ProjectPlan project -> collectRelations(project.child(), names);
      case SortPlan sort -> collectRelations(sort.child(), names);
      case JoinPlan join -> {
        collectRelations(join.left(), names);
        collectRelations(join.right(), names);
      }
      default -> {}
    }
  }

  private static void add(Set<String> names, String relation) {
    if (relation != null && !relation.isBlank()) names.add(relation);
  }

  private static boolean reorderable(JoinKind kind) {
    return kind == JoinKind.CARTESIAN || kind == JoinKind.THETA;
  }

  private static List<int[]> permutations(int[] values) {
    List<int[]> result = new ArrayList<>();
    permute(values, 0, result);
    return result;
  }

  private static void permute(int[] values, int index, List<int[]> result) {
    if (index == values.length) {
      result.add(values.clone());
      return;
    }
    for (int i = index; i < values.length; i++) {
      swap(values, index, i);
      permute(values, index + 1, result);
      swap(values, index, i);
    }
  }

  private static void swap(int[] values, int first, int second) {
    int temporary = values[first];
    values[first] = values[second];
    values[second] = temporary;
  }

  private record Flat(List<PlanNode> leaves, List<Expression> conditions) {}
}
