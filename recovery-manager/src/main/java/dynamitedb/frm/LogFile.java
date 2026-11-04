package dynamitedb.frm;

import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Milestone 1 mock only: fixture responses/call recording, no durable DBMS behavior. */
public final class LogFile {
  private final MockRecoveryContext context;

  public LogFile(MockRecoveryContext context) {
    this.context = Objects.requireNonNull(context, "context");
  }

  public void appendFrame(ImmutableBytes frame) {
    Objects.requireNonNull(frame, "frame");
    context.invoked("LogFile.appendFrame");
  }

  public void sync() {
    context.invoked("LogFile.sync");
  }

  public Iterator<ImmutableBytes> readFrames() {
    context.invoked("LogFile.readFrames");
    return List.of(context.frame()).iterator();
  }

  public void repairIncompleteTail() {
    context.invoked("LogFile.repairIncompleteTail");
  }
}
