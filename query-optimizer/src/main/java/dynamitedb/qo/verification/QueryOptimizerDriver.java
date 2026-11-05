package dynamitedb.qo.verification;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.testing.TestReport;
import java.util.List;

/** Menguji QO lewat port QueryOptimizer tanpa Storage Manager asli (QO-16). */
public class QueryOptimizerDriver {

  private final QueryOptimizer optimizer;

  public QueryOptimizerDriver(QueryOptimizer optimizer) {
    this.optimizer = optimizer;
  }

  public TestReport runSuite(List<String> queries) {
    // TODO: jalankan tiap query dan catat lulus/gagal
    return new TestReport(List.of());
  }
}
