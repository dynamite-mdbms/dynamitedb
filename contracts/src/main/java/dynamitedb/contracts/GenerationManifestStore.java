package dynamitedb.contracts;

public interface GenerationManifestStore {
  GenerationManifest readActive();

  void publishAtomically(GenerationManifest manifest);
}
