package dynamitedb.contracts.error;

// draf dari QP biar bisa compile, sesuai shared types final
public enum ErrorCode {
  SQL_SYNTAX,
  SQL_BIND,
  INVALID_PLAN,
  ACCESS_ABORTED,
  WAL_FAILURE,
  STORAGE_FAILURE,
  RECOVERY_FAILURE,
  INVALID_STATE,
  UNSUPPORTED_OPERATION,
  CORRUPT_LOG
}
