package dynamitedb.qo.join;

import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.UpdatePlan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Membangkitkan kandidat urutan dan pengelompokan join (QO-12). */
public class JoinOrderOptimizer {

  private static final int MAX_LEAVES = 6;
  private static final int MAX_CANDIDATES = 64;

  public List<PlanNode> enumerate(PlanNode plan) {
    Objects.requireNonNull(plan, "plan");
    return List.copyOf(variants(plan, MAX_CANDIDATES));
  }

  private List<PlanNode> variants(PlanNode plan, int limit) {
    return switch (plan) {
      case JoinPlan join when reorderable(join.kind()) -> joinVariants(join, limit);
      case JoinPlan join ->
          cross(
              join.left(),
              join.right(),
              limit,
              (left, right) -> new JoinPlan(left, right, join.kind(), join.condition()));
      case ProjectPlan project ->
          map(project.child(), limit, child -> new ProjectPlan(child, project.columns()));
      case SortPlan sort -> map(sort.child(), limit, child -> new SortPlan(child, sort.keys()));
      case FilterPlan filter ->
          map(filter.child(), limit, child -> new FilterPlan(child, filter.predicate()));
      case UpdatePlan update ->
          map(
              update.source(),
              limit,
              source -> new UpdatePlan(source, update.table(), update.target(), update.value()));
      case DeletePlan delete ->
          map(delete.source(), limit, source -> new DeletePlan(source, delete.table()));
      default -> List.of(plan);
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

  private List<PlanNode> map(PlanNode plan, int limit, Function<PlanNode, PlanNode> mapper) {
    List<PlanNode> mapped = new ArrayList<>();
    for (PlanNode variant : variants(plan, limit)) mapped.add(mapper.apply(variant));
    return mapped;
  }

  private List<PlanNode> cross(
      PlanNode left, PlanNode right, int limit, BiFunction<PlanNode, PlanNode, PlanNode> combiner) {
    List<PlanNode> leftVariants = variants(left, limit);
    List<PlanNode> rightVariants = variants(right, limit);
    List<PlanNode> combined = new ArrayList<>();
    for (PlanNode leftVariant : leftVariants) {
      for (PlanNode rightVariant : rightVariants) {
        combined.add(combiner.apply(leftVariant, rightVariant));
        if (combined.size() >= limit) return combined;
      }
    }
    return combined;
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
