package dynamitedb.qo.join;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.PlanNode;
import dynamitedb.qo.pipeline.PlanRewrite;

/** Memilih pembacaan lewat index atau scan sekuensial (QO-13). */
public class AccessPathOptimizer implements PlanRewrite {

  private final CatalogAccess catalog;

  public AccessPathOptimizer(CatalogAccess catalog) {
    this.catalog = catalog;
  }

  @Override
  public PlanNode rewrite(PlanNode plan) {
    // TODO: pakai index jika tersedia dan cocok, HASH hanya untuk kesamaan
    return plan;
  }
}
