package dynamitedb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalShellOptionsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void defaultsToConfiguredDataDirectoryAndRecognizesHelp() {
    assertEquals(
        DbmsConfig.defaults().dataDirectory(), LocalShell.parse(new String[0]).dataDirectory());
    assertTrue(LocalShell.parse(new String[] {"--help"}).help());
  }

  @Test
  void parsesDataDirectoryAndRejectsUnknownOrMissingOptions() {
    Path directory = temporaryDirectory.resolve("database");
    assertEquals(
        directory,
        LocalShell.parse(new String[] {"--data-dir", directory.toString()}).dataDirectory());
    assertThrows(IllegalArgumentException.class, () -> LocalShell.parse(new String[] {"--bogus"}));
    assertThrows(
        IllegalArgumentException.class, () -> LocalShell.parse(new String[] {"--data-dir"}));
    assertThrows(
        IllegalArgumentException.class, () -> LocalShell.parse(new String[] {"--data-dir", ""}));
  }

  @Test
  void createsMissingDirectoryButRejectsFilesAndUnrelatedNonemptyDirectories() throws IOException {
    Path database = temporaryDirectory.resolve("database");
    assertEquals(database.toAbsolutePath().normalize(), LocalShell.prepareDataDirectory(database));
    assertTrue(Files.isDirectory(database));

    Path file = temporaryDirectory.resolve("file");
    Files.writeString(file, "user data");
    assertThrows(IllegalArgumentException.class, () -> LocalShell.prepareDataDirectory(file));

    Path unrelated = temporaryDirectory.resolve("unrelated");
    Files.createDirectory(unrelated);
    Path userFile = Files.writeString(unrelated.resolve("notes.txt"), "keep me");
    assertThrows(IllegalArgumentException.class, () -> LocalShell.prepareDataDirectory(unrelated));
    assertTrue(Files.exists(userFile));

    Path recognizable = temporaryDirectory.resolve("recognizable");
    Files.createDirectory(recognizable);
    Files.createFile(recognizable.resolve("active-generation.properties"));
    assertEquals(
        recognizable.toAbsolutePath().normalize(), LocalShell.prepareDataDirectory(recognizable));
  }

  @Test
  void helpDoesNotCreateDatabaseDirectory() throws Exception {
    Path database = temporaryDirectory.resolve("not-created");
    java.io.StringWriter output = new java.io.StringWriter();
    LocalShell.run(
        new String[] {"--help", "--data-dir", database.toString()},
        new java.io.PrintWriter(output, true));
    assertTrue(output.toString().contains("database folder"));
    assertTrue(output.toString().contains(":read PATH"));
    assertTrue(output.toString().contains("compatible UTF-8 SQL script"));
    assertTrue(output.toString().contains("relative to the current directory"));
    assertTrue(output.toString().contains("stops at the first error"));
    assertTrue(output.toString().contains("Up/Down"));
    assertFalse(Files.exists(database));
  }
}
