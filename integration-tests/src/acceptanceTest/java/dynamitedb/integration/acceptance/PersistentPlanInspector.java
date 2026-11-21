package dynamitedb.integration.acceptance;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.qo.binding.Binder;
import dynamitedb.qo.binding.CostEstimator;
import dynamitedb.qo.facade.QueryOptimizerImpl;
import dynamitedb.qo.join.AccessPathOptimizer;
import dynamitedb.qo.join.BestPlanSelector;
import dynamitedb.qo.join.JoinOrderOptimizer;
import dynamitedb.qo.join.JoinRewriter;
import dynamitedb.qo.parser.ExpressionParser;
import dynamitedb.qo.parser.Lexer;
import dynamitedb.qo.parser.SqlParser;
import dynamitedb.qo.parser.StatementParser;
import dynamitedb.qo.pipeline.OptimizerPipeline;
import dynamitedb.qo.pipeline.PlanRewrite;
import dynamitedb.qo.plan.PlanBuilder;
import dynamitedb.qo.plan.PlanVisualizer;
import dynamitedb.qo.pushdown.ProjectionPushdown;
import dynamitedb.qo.pushdown.SelectionPushdown;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.catalog.StorageCatalogAdapter;
import java.nio.file.Path;
import java.util.List;

/** Inspects the production optimizer stages using the catalog persisted by the packaged server. */
final class PersistentPlanInspector {
  private PersistentPlanInspector() {}

  static QueryOptimizer open(Path dataDirectory) {
    var catalog = new StorageCatalogAdapter(new CatalogManager(dataDirectory));
    var cost = new CostEstimator(catalog);
    return new QueryOptimizerImpl(
        new Lexer(),
        new SqlParser(new StatementParser(), new ExpressionParser()),
        new Binder(catalog),
        new PlanBuilder(),
        new OptimizerPipeline(
            List.<PlanRewrite>of(
                new SelectionPushdown(cost),
                new ProjectionPushdown(catalog),
                new JoinRewriter(),
                new AccessPathOptimizer(catalog)),
            new JoinOrderOptimizer(catalog),
            new BestPlanSelector(cost)),
        cost,
        new PlanVisualizer());
  }
}
