package dynamitedb.frm.mock;

import dynamitedb.frm.AtomicGenerationManifestStore;
import dynamitedb.frm.CheckpointManager;
import dynamitedb.frm.CommitClock;
import dynamitedb.frm.CrashRecoveryManager;
import dynamitedb.frm.HistoricalRecoveryManager;
import dynamitedb.frm.HistoryCatalog;
import dynamitedb.frm.LogFile;
import dynamitedb.frm.LogManager;
import dynamitedb.frm.LogRecordCodec;
import dynamitedb.frm.RecoveryInitializer;
import dynamitedb.frm.RecoveryManagerImpl;
import dynamitedb.frm.RecoveryTestDriver;
import dynamitedb.frm.UndoManager;
import dynamitedb.frm.WalService;
import java.nio.file.Path;
import java.util.Objects;

/** Wires only FRM mocks and fixture ports. Construction does not create files or start threads. */
public final class MockRecoveryEnvironment {
  private final MockRecoveryContext context;
  private final AtomicGenerationManifestStore manifests;
  private final MockRecoveryPorts ports;
  private final LogFile file;
  private final LogRecordCodec codec;
  private final LogManager log;
  private final CommitClock clock;
  private final WalService wal;
  private final UndoManager undo;
  private final HistoryCatalog catalog;
  private final HistoricalRecoveryManager historical;
  private final CheckpointManager checkpoint;
  private final CrashRecoveryManager crash;
  private final RecoveryManagerImpl recovery;
  private final RecoveryInitializer initializer;

  public MockRecoveryEnvironment(Path root, MockRecoveryContext context) {
    Objects.requireNonNull(root, "root");
    this.context = Objects.requireNonNull(context, "context");
    manifests = new AtomicGenerationManifestStore(root, context.manifest(), context);
    ports = new MockRecoveryPorts(context, manifests);
    file = new LogFile(context);
    codec = new LogRecordCodec(context);
    log = new LogManager(root, context.lineage(), file, codec, context);
    clock = new CommitClock(context);
    wal = new WalService(clock, log, context);
    undo = new UndoManager(log, ports.pages(), context);
    catalog = new HistoryCatalog(context);
    historical = new HistoricalRecoveryManager(context);
    checkpoint = new CheckpointManager(ports.coordinator(), ports.pages(), log, context);
    crash =
        new CrashRecoveryManager(
            log, wal, manifests, ports.pages(), undo, ports.generations(), context);
    recovery = new RecoveryManagerImpl(wal, undo, crash, historical, checkpoint);
    initializer = new RecoveryInitializer(manifests, recovery, context);
  }

  public static MockRecoveryEnvironment create() {
    return new MockRecoveryEnvironment(Path.of("mock-data"), new MockRecoveryContext());
  }

  public MockRecoveryContext context() {
    return context;
  }

  public AtomicGenerationManifestStore manifests() {
    return manifests;
  }

  public MockRecoveryPorts ports() {
    return ports;
  }

  public LogFile file() {
    return file;
  }

  public LogRecordCodec codec() {
    return codec;
  }

  public LogManager log() {
    return log;
  }

  public CommitClock clock() {
    return clock;
  }

  public WalService wal() {
    return wal;
  }

  public UndoManager undo() {
    return undo;
  }

  public HistoryCatalog catalog() {
    return catalog;
  }

  public HistoricalRecoveryManager historical() {
    return historical;
  }

  public CheckpointManager checkpoint() {
    return checkpoint;
  }

  public CrashRecoveryManager crash() {
    return crash;
  }

  public RecoveryManagerImpl recovery() {
    return recovery;
  }

  public RecoveryInitializer initializer() {
    return initializer;
  }

  public RecoveryTestDriver driver() {
    return new RecoveryTestDriver(this);
  }
}
