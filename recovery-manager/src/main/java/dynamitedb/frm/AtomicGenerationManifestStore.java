package dynamitedb.frm;

import dynamitedb.contracts.GenerationManifest;
import dynamitedb.contracts.GenerationManifestStore;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.nio.file.Path;
import java.util.Objects;

/** In-memory Milestone 1 mock. The class name does not imply durable/atomic disk publication. */
public final class AtomicGenerationManifestStore implements GenerationManifestStore {
  private final Path root;
  private final MockRecoveryContext context;
  private GenerationManifest active;

  public AtomicGenerationManifestStore(
      Path root, GenerationManifest initial, MockRecoveryContext context) {
    this.root = Objects.requireNonNull(root, "root");
    this.active = Objects.requireNonNull(initial, "initial");
    this.context = Objects.requireNonNull(context, "context");
  }

  @Override
  public synchronized GenerationManifest readActive() {
    context.invoked("AtomicGenerationManifestStore.readActive");
    return active;
  }

  @Override
  public synchronized void publishAtomically(GenerationManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    context.invoked("AtomicGenerationManifestStore.publishAtomically");
    active = manifest;
  }
}
