package dynamitedb.qp.execution;

import dynamitedb.contracts.transaction.TransactionId;

/** Data yang dibawa selama satu query jalan. */
// di diagram parameter "context" ga ada tipenya, jadi bikin kelas ini (perlu dimasukin ke diagram)
// sementara isinya transactionId doang, Session (QP1) belum ada
public record ExecutionContext(TransactionId transactionId) {}
