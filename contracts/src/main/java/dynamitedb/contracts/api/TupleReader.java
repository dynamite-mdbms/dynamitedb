package dynamitedb.contracts.api;

import dynamitedb.contracts.storage.IndexPredicate;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.StoredRow;
import dynamitedb.contracts.transaction.TransactionId;
import java.util.Optional;

/** Pembacaan baris dari SM setelah QP mendapat izin CCM (SM-04, SM-11). */
public interface TupleReader {
  RowCursor openTableScan(String table, TransactionId tx);

  RowCursor openIndexScan(String index, IndexPredicate predicate, TransactionId tx);

  Optional<StoredRow> fetch(RecordId rid, TransactionId tx);
}
