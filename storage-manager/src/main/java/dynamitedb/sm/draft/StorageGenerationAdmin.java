package dynamitedb.sm.draft;

/** Temporary FR4-owned port for activation after a single authoritative manifest publication. */
public interface StorageGenerationAdmin {
  GenerationManifest readActiveManifest();

  void refreshPublishedGeneration(GenerationManifest manifest);
}
