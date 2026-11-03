package dynamitedb.qo.facade;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.PlanExplanation;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.qo.binding.Binder;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.pipeline.OptimizerPipeline;
import dynamitedb.qo.plan.PlanBuilder;
import dynamitedb.qo.plan.PlanVisualizer;
import java.util.List;
import java.util.Objects;

/** Pintu masuk QO untuk QP: parse, bind, lalu optimasi. */
public class QueryOptimizerImpl implements QueryOptimizer {

  private final Lexer lexer;
  private final SqlParser parser;
  private final Binder binder;
  private final PlanBuilder builder;
  private final OptimizerPipeline pipeline;
  private final CostEstimator cost;
  private final PlanVisualizer visualizer;

  public QueryOptimizerImpl(
      Lexer lexer,
      SqlParser parser,
      Binder binder,
      PlanBuilder builder,
      OptimizerPipeline pipeline,
      CostEstimator cost,
      PlanVisualizer visualizer) {
    this.lexer = lexer;
    this.parser = parser;
    this.binder = binder;
    this.builder = builder;
    this.pipeline = pipeline;
    this.cost = cost;
    this.visualizer = visualizer;
  }

  @Override
  public PreparedQuery prepare(String sql) {
    PlanNode initial = buildInitialPlan(sql);
    PlanNode optimized = shouldOptimize(initial) ? pipeline.optimize(initial) : initial;
    return new PreparedQuery(optimized, new Schema(List.of()));
  }

  @Override
  public double estimateCost(PlanNode plan) {
    return cost.estimate(Objects.requireNonNull(plan, "plan"));
  }

  @Override
  public PlanExplanation explain(String sql) {
    PlanNode initial = buildInitialPlan(sql);
    PlanNode optimized = shouldOptimize(initial) ? pipeline.optimize(initial) : initial;
    return new PlanExplanation(
        visualizer.render(initial), visualizer.render(optimized), cost.estimate(optimized));
  }

  private PlanNode buildInitialPlan(String sql) {
    var tokens = lexer.tokenize(sql);
    var statement = parser.parse(tokens);
    var bound = binder.bind(statement);
    return builder.build(bound);
  }

  private static boolean shouldOptimize(PlanNode plan) {
    return !(plan instanceof TransactionCommandPlan) && !(plan instanceof CreateIndexPlan);
  }
}
