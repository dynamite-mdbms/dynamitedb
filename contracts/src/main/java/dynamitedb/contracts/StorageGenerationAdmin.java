package dynamitedb.contracts;

public interface StorageGenerationAdmin {
  GenerationManifest readActiveManifest();

  void switchToPublishedGeneration(GenerationManifest manifest);
}
