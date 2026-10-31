package dynamitedb.qo.facade;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.query.PlanExplanation;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.TableScanPlan;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.qo.binding.Binder;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.pipeline.OptimizerPipeline;
import dynamitedb.qo.plan.PlanBuilder;
import dynamitedb.qo.plan.PlanVisualizer;
import java.util.List;

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
    // TODO: alur penuh, perintah transaksi dan CREATE INDEX melewati optimasi
    return new PreparedQuery(new TableScanPlan("mock", "mock"), new Schema(List.of()));
  }

  @Override
  public double estimateCost(PlanNode plan) {
    // TODO: delegasi ke CostEstimator
    return 0.0;
  }

  @Override
  public PlanExplanation explain(String sql) {
    // TODO: plan sebelum dan sesudah optimasi beserta biayanya
    return new PlanExplanation("", "", 0.0);
  }
}
