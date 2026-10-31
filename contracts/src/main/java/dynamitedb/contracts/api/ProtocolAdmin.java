package dynamitedb.contracts.api;

import dynamitedb.contracts.transaction.ProtocolType;

public interface ProtocolAdmin {
  void setProtocol(ProtocolType type);

  ProtocolType activeProtocol();
}
