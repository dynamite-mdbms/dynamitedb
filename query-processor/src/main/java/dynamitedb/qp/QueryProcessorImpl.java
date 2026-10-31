package dynamitedb.qp;

import dynamitedb.contracts.api.QueryOptimizer;
import dynamitedb.contracts.api.QueryProcessor;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.query.CreateIndexPlan;
import dynamitedb.contracts.query.PreparedQuery;
import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;
import dynamitedb.contracts.query.TransactionCommand;
import dynamitedb.contracts.query.TransactionCommandPlan;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.qp.execution.ExecutionEngine;
import dynamitedb.qp.session.SessionContext;
import dynamitedb.qp.session.SessionManager;
import dynamitedb.qp.transaction.TransactionCoordinator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/** QP1 request boundary: prepare, execute, implicit transactions, and session cleanup. */
public final class QueryProcessorImpl implements QueryProcessor {
  private final QueryOptimizer optimizer;
  private final SessionManager sessions;
  private final BiFunction<PreparedQuery, SessionContext, QueryResult> execution;
  private final TransactionCoordinator txCoordinator;

  public QueryProcessorImpl(
      QueryOptimizer optimizer,
      SessionManager sessions,
      ExecutionEngine engine,
      TransactionCoordinator txCoordinator) {
    this(optimizer, sessions, Objects.requireNonNull(engine, "engine")::execute, txCoordinator);
  }

  /** Injection seam for isolated drivers while QP2's implementation is still a stub. */
  public QueryProcessorImpl(
      QueryOptimizer optimizer,
      SessionManager sessions,
      BiFunction<PreparedQuery, SessionContext, QueryResult> execution,
      TransactionCoordinator txCoordinator) {
    this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.execution = Objects.requireNonNull(execution, "execution");
    this.txCoordinator = Objects.requireNonNull(txCoordinator, "txCoordinator");
  }

  @Override
  public SessionId openSession(String clientId) {
    return sessions.create(clientId);
  }

  @Override
  public QueryResult execute(SessionId sessionId, String sql) {
    SessionContext session = sessions.get(sessionId);
    synchronized (session) {
      session.requireOpen();
      if (sql == null || sql.isBlank()) {
        throw new DbmsException(ErrorCode.SQL_SYNTAX, "SQL must not be empty");
      }
      // Preparation errors have no data effects and leave an existing transaction available.
      PreparedQuery prepared = optimizer.prepare(sql);
      if (prepared == null || prepared.root() == null || prepared.outputSchema() == null) {
        throw new DbmsException(ErrorCode.INVALID_PLAN, "Optimizer returned an incomplete plan");
      }
      if (prepared.root() instanceof TransactionCommandPlan command) {
        try {
          return executePrepared(prepared, session);
        } catch (RuntimeException failure) {
          if (command.command() == TransactionCommand.COMMIT) {
            abortAfterFailure(session, failure);
          }
          throw failure;
        }
      }
      if (prepared.root() instanceof CreateIndexPlan && session.activeTx().isPresent()) {
        throw new DbmsException(
            ErrorCode.INVALID_STATE, "CREATE INDEX is only supported outside BEGIN transactions");
      }
      boolean implicit = session.activeTx().isEmpty();
      try {
        if (implicit) {
          txCoordinator.begin(session);
        }
        QueryResult result = executePrepared(prepared, session);
        if (implicit) {
          txCoordinator.commit(session);
        }
        return result;
      } catch (RuntimeException failure) {
        abortAfterFailure(session, failure);
        throw failure;
      }
    }
  }

  private QueryResult executePrepared(PreparedQuery prepared, SessionContext session) {
    QueryResult result = execution.apply(prepared, session);
    if (result == null || result.schema() == null || result.rows() == null) {
      throw new DbmsException(ErrorCode.INVALID_PLAN, "Execution returned an incomplete result");
    }
    var columns = result.schema().columns();
    if (columns == null
        || columns.stream()
            .anyMatch(
                column ->
                    column == null
                        || column.relation() == null
                        || column.name() == null
                        || column.dataType() == null)
        || result.rows().stream()
            .anyMatch(
                row ->
                    row == null
                        || row.values() == null
                        || row.values().size() != columns.size()
                        || row.values().stream().anyMatch(Objects::isNull))) {
      throw new DbmsException(ErrorCode.INVALID_PLAN, "Execution result does not match its schema");
    }
    return new QueryResult(
        new Schema(List.copyOf(columns)),
        result.rows().stream().map(row -> new Row(List.copyOf(row.values()))).toList(),
        result.affectedRows(),
        result.message() == null ? "" : result.message());
  }

  private void abortAfterFailure(SessionContext session, RuntimeException failure) {
    if (session.activeTx().isPresent()) {
      try {
        txCoordinator.abort(session);
      } catch (RuntimeException rollbackFailure) {
        // Keep the active context available: failed rollback must not silently lose protection.
        if (rollbackFailure != failure) {
          failure.addSuppressed(rollbackFailure);
        }
      }
    }
  }

  @Override
  public void closeSession(SessionId sessionId) {
    sessions
        .find(sessionId)
        .ifPresent(
            session -> {
              synchronized (session) {
                if (session.activeTx().isPresent()) {
                  txCoordinator.abort(session);
                }
                sessions.close(sessionId);
              }
            });
  }
}
