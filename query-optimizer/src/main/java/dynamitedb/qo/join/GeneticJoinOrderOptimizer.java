package dynamitedb.qo.join;

import dynamitedb.contracts.query.AggregatePlan;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.query.Expression;
import dynamitedb.contracts.query.FilterPlan;
import dynamitedb.contracts.query.IndexScanPlan;
import dynamitedb.contracts.query.InsertPlan;
import dynamitedb.contracts.query.JoinKind;
import dynamitedb.contracts.query.JoinPlan;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.ProjectPlan;
import dynamitedb.contracts.query.SortPlan;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.query.UpdatePlan;
import dynamitedb.qo.binding.CostEstimator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;


public final class GeneticJoinOrderOptimizer {

  private final CostEstimator costs;
  private final Config config;
  private final Random random;

  public GeneticJoinOrderOptimizer(CostEstimator costs, Config config, long seed) {
    this.costs = Objects.requireNonNull(costs, "costs");
    this.config = Objects.requireNonNull(config, "config");
    this.random = new Random(seed);
  }


  public OptimizationResult optimize(PlanNode original) {
    Objects.requireNonNull(original, "original");
    if (original instanceof JoinPlan) return optimizeJoin(original);
    double baselineCost = costs.estimate(original);
    PlanNode bestPlan = rewrite(original);
    double bestCost = costs.estimate(bestPlan);
    if (baselineCost <= bestCost) {
      return new OptimizationResult(
          original, baselineCost, original, baselineCost, List.of(original));
    }
    return new OptimizationResult(original, baselineCost, bestPlan, bestCost, List.of(bestPlan));
  }

  private PlanNode rewrite(PlanNode node) {
    return switch (node) {
      case JoinPlan join -> {
        PlanNode rewritten =
            new JoinPlan(
                rewrite(join.left()), rewrite(join.right()), join.kind(), join.condition());
        if (join.kind() == JoinKind.CARTESIAN || join.kind() == JoinKind.THETA) {
          yield optimizeJoin(rewritten).bestPlan();
        }
        yield rewritten;
      }
      case FilterPlan filter -> new FilterPlan(rewrite(filter.child()), filter.predicate());
      case ProjectPlan project -> ProjectPlan.fromItems(rewrite(project.child()), project.items());
      case SortPlan sort -> new SortPlan(rewrite(sort.child()), sort.keys());
      case AggregatePlan aggregate ->
          new AggregatePlan(
              rewrite(aggregate.child()), aggregate.groupBy(), aggregate.aggregates());
      case DeletePlan delete -> new DeletePlan(rewrite(delete.source()), delete.table());
      case UpdatePlan update ->
          new UpdatePlan(rewrite(update.source()), update.table(), update.assignments());
      case TableScanPlan scan -> scan;
      case IndexScanPlan scan -> scan;
      case InsertPlan insert -> insert;
      case CreateIndexPlan index -> index;
      case TransactionCommandPlan command -> command;
    };
  }

  private OptimizationResult optimizeJoin(PlanNode original) {
    FlatJoin flat = flatten(original);
    double baselineCost = costs.estimate(original);
    if (flat == null || flat.leaves().size() < 2 || flat.leaves().size() > config.maxTables()) {
      return new OptimizationResult(
          original, baselineCost, original, baselineCost, List.of(original));
    }

    List<Integer> naturalOrder = new ArrayList<>();
    for (int index = 0; index < flat.leaves().size(); index++) naturalOrder.add(index);
    List<List<Integer>> allOrders = new ArrayList<>();
    permute(new ArrayList<>(naturalOrder), 0, allOrders);
    Collections.shuffle(allOrders, random);
    int targetSize = Math.min(config.populationSize(), allOrders.size());
    Map<List<Integer>, Candidate> population = new LinkedHashMap<>();
    for (int index = 0; index < targetSize; index++) {
      add(population, allOrders.get(index), flat);
    }

    for (int generation = 0; generation < config.generations(); generation++) {
      List<Candidate> ranked = ranked(population);
      Map<List<Integer>, Candidate> next = new LinkedHashMap<>();
      int eliteCount = Math.min(config.eliteCount(), ranked.size());
      for (int index = 0; index < eliteCount; index++) {
        Candidate elite = ranked.get(index);
        next.put(elite.order(), elite);
      }
      int attempts = 0;
      while (next.size() < targetSize && attempts++ < targetSize * 20) {
        Candidate parentA = tournament(ranked);
        Candidate parentB = tournament(ranked);
        List<Integer> child = crossover(parentA.order(), parentB.order());
        mutate(child);
        add(next, child, flat);
      }

      int fill = 0;
      while (next.size() < targetSize) {
        add(next, allOrders.get(fill++), flat);
      }
      population = next;
    }

    List<Candidate> ranked = ranked(population);
    Candidate best = ranked.get(0);

    if (baselineCost <= best.cost()) {
      return new OptimizationResult(original, baselineCost, original, baselineCost, plans(ranked));
    }
    return new OptimizationResult(original, baselineCost, best.plan(), best.cost(), plans(ranked));
  }

  private void add(Map<List<Integer>, Candidate> population, List<Integer> order, FlatJoin flat) {
    List<Integer> chromosome = List.copyOf(order);
    if (!population.containsKey(chromosome)) {
      PlanNode plan = rebuild(flat, chromosome);
      population.put(chromosome, new Candidate(chromosome, plan, costs.estimate(plan)));
    }
  }

  private Candidate tournament(List<Candidate> ranked) {
    Candidate winner = null;
    for (int count = 0; count < config.tournamentSize(); count++) {
      Candidate candidate = ranked.get(random.nextInt(ranked.size()));
      if (winner == null || candidate.cost() < winner.cost()) winner = candidate;
    }
    return winner;
  }

  private List<Integer> crossover(List<Integer> first, List<Integer> second) {
    int size = first.size();
    int from = random.nextInt(size);
    int to = random.nextInt(size);
    if (from > to) {
      int temporary = from;
      from = to;
      to = temporary;
    }
    List<Integer> child = new ArrayList<>(Collections.nCopies(size, -1));
    HashSet<Integer> used = new HashSet<>();
    for (int index = from; index <= to; index++) {
      child.set(index, first.get(index));
      used.add(first.get(index));
    }
    int target = (to + 1) % size;
    for (int offset = 0; offset < size; offset++) {
      int gene = second.get((to + 1 + offset) % size);
      if (used.add(gene)) {
        child.set(target, gene);
        target = (target + 1) % size;
      }
    }
    return child;
  }

  private void mutate(List<Integer> chromosome) {
    if (random.nextDouble() >= config.mutationProbability() || chromosome.size() < 2) return;
    int first = random.nextInt(chromosome.size());
    int second = random.nextInt(chromosome.size() - 1);
    if (second >= first) second++;
    Collections.swap(chromosome, first, second);
  }

  private static List<Candidate> ranked(Map<List<Integer>, Candidate> population) {
    return population.values().stream()
        .sorted(Comparator.comparingDouble(Candidate::cost))
        .toList();
  }

  private static List<PlanNode> plans(List<Candidate> candidates) {
    return candidates.stream().map(Candidate::plan).toList();
  }

  private static void permute(List<Integer> order, int start, List<List<Integer>> result) {
    if (start == order.size()) {
      result.add(List.copyOf(order));
      return;
    }
    for (int index = start; index < order.size(); index++) {
      Collections.swap(order, start, index);
      permute(order, start + 1, result);
      Collections.swap(order, start, index);
    }
  }

  private static FlatJoin flatten(PlanNode node) {
    if (!(node instanceof JoinPlan join)) return null;
    List<PlanNode> leaves = new ArrayList<>();
    List<Expression> conditions = new ArrayList<>();
    if (!flattenSupported(join, leaves, conditions)) return null;
    return new FlatJoin(List.copyOf(leaves), List.copyOf(conditions));
  }

  private static boolean flattenSupported(
      JoinPlan join, List<PlanNode> leaves, List<Expression> conditions) {
    if (join.kind() == JoinKind.NATURAL) {
      leaves.add(join);
      return true;
    }
    if (join.kind() != JoinKind.CARTESIAN && join.kind() != JoinKind.THETA) return false;
    if (join.left() instanceof JoinPlan left && !flattenSupported(left, leaves, conditions)) {
      return false;
    }
    if (!(join.left() instanceof JoinPlan)) leaves.add(join.left());
    if (join.right() instanceof JoinPlan right && !flattenSupported(right, leaves, conditions)) {
      return false;
    }
    if (!(join.right() instanceof JoinPlan)) leaves.add(join.right());
    join.condition().ifPresent(conditions::add);
    return true;
  }

  private static PlanNode rebuild(FlatJoin flat, List<Integer> order) {
    PlanNode plan = flat.leaves().get(order.get(0));
    for (int index = 1; index < order.size(); index++) {
      plan =
          new JoinPlan(
              plan,
              flat.leaves().get(order.get(index)),
              JoinKind.CARTESIAN,
              java.util.Optional.empty());
    }
    if (flat.conditions().isEmpty()) return plan;
    Expression condition = PlanExpressions.and(flat.conditions());
    JoinPlan top = (JoinPlan) plan;
    return new JoinPlan(top.left(), top.right(), JoinKind.THETA, java.util.Optional.of(condition));
  }

  public record Config(
      int populationSize,
      int generations,
      double mutationProbability,
      int tournamentSize,
      int eliteCount,
      int maxTables) {
    public Config {
      if (populationSize < 2)
        throw new IllegalArgumentException("populationSize must be at least 2");
      if (generations < 0) throw new IllegalArgumentException("generations must not be negative");
      if (mutationProbability < 0 || mutationProbability > 1) {
        throw new IllegalArgumentException("mutationProbability must be between 0 and 1");
      }
      if (tournamentSize < 1 || tournamentSize > populationSize) {
        throw new IllegalArgumentException("tournamentSize must be within the population size");
      }
      if (eliteCount < 1 || eliteCount >= populationSize) {
        throw new IllegalArgumentException("eliteCount must be positive and below populationSize");
      }
      if (maxTables < 2) throw new IllegalArgumentException("maxTables must be at least 2");
    }

    public static Config defaults() {
      return new Config(32, 50, 0.15, 3, 2, 8);
    }
  }

  public record OptimizationResult(
      PlanNode baseline,
      double baselineCost,
      PlanNode bestPlan,
      double bestCost,
      List<PlanNode> candidates) {
    public OptimizationResult {
      candidates = List.copyOf(candidates);
    }
  }

  private record Candidate(List<Integer> order, PlanNode plan, double cost) {}

  private record FlatJoin(List<PlanNode> leaves, List<Expression> conditions) {}
}
