package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.GenerationManifest;

/** Mengaktifkan generasi storage setelah manifest dipublikasikan FRM (FR-05). */
public interface StorageGenerationAdmin {
  GenerationManifest readActiveManifest();

  void switchToPublishedGeneration(GenerationManifest manifest);
}
