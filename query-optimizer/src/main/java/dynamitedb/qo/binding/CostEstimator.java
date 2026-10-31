package dynamitedb.qo.binding;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.PlanNode;

/** Mengestimasi biaya dan kardinalitas plan dari statistik katalog (QO-08). */
public class CostEstimator {

  private final CatalogAccess catalog;

  public CostEstimator(CatalogAccess catalog) {
    this.catalog = catalog;
  }

  public double estimate(PlanNode plan) {
    // TODO: akumulasi biaya seluruh node, join memakai rumus BNLJ
    return 0.0;
  }

  public long estimateRows(PlanNode plan) {
    // TODO: estimasi baris untuk seleksi, cartesian product, dan join
    return 0;
  }
}
