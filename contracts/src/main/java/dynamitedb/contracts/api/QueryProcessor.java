package dynamitedb.contracts.api;

import dynamitedb.contracts.query.QueryResult;
import dynamitedb.contracts.query.SessionId;

/** Client-facing QP boundary; component implementations stay behind this interface. */
public interface QueryProcessor {
  SessionId openSession(String clientId);

  QueryResult execute(SessionId sessionId, String sql);

  void closeSession(SessionId sessionId);
}
