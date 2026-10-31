package dynamitedb.frm;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Objects;

/** Milestone 1 mock only: fixture responses/call recording, no durable DBMS behavior. */
public final class LogRecordCodec {
  private final MockRecoveryContext context;

  public LogRecordCodec(MockRecoveryContext context) {
    this.context = Objects.requireNonNull(context, "context");
  }

  public ImmutableBytes encode(LogRecord record) {
    Objects.requireNonNull(record, "record");
    context.invoked("LogRecordCodec.encode");
    return context.frame();
  }

  public LogRecord decode(ImmutableBytes frame) {
    Objects.requireNonNull(frame, "frame");
    context.invoked("LogRecordCodec.decode");
    if (!frame.equals(context.frame())) {
      throw new DbmsException(ErrorCode.CORRUPT_LOG, "Unknown mock frame");
    }
    return context.record();
  }

  public boolean verify(ImmutableBytes frame) {
    Objects.requireNonNull(frame, "frame");
    context.invoked("LogRecordCodec.verify");
    return frame.equals(context.frame());
  }
}
