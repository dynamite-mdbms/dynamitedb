package dynamitedb.qp.write;

import dynamitedb.contracts.query.DeletePlan;
import dynamitedb.contracts.transaction.TransactionId;

// draf dari QP5 biar bisa compile, nanti ganti punya QP3
public class DeleteExecutor {

  public long execute(DeletePlan plan, TransactionId tx) {
    return 0;
  }
}
