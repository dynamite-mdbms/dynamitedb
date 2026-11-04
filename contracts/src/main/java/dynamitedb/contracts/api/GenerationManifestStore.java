package dynamitedb.contracts.api;

import dynamitedb.contracts.recovery.GenerationManifest;

/** Penyimpanan manifest generasi milik FRM, SM hanya membaca manifest aktif. */
public interface GenerationManifestStore {
  GenerationManifest readActive();

  void publishAtomically(GenerationManifest manifest);
}
