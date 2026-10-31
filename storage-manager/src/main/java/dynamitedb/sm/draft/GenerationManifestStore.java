package dynamitedb.sm.draft;

/** Temporary FR4-owned durable publication port; its concrete implementation belongs to FRM. */
public interface GenerationManifestStore {
  GenerationManifest readActive();

  void publishAtomically(GenerationManifest manifest);
}
