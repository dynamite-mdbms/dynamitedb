package dynamitedb.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Independent snapshot assertions for documented public declarations and provider relationships.
 */
class CanonicalStructureTest {
  @TestFactory
  Stream<DynamicTest> canonicalDeclarationsAndProviders() throws Exception {
    var stream = getClass().getResourceAsStream("/canonical-structure.tsv");
    assertNotNull(stream);
    List<String> lines;
    try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      lines = reader.lines().filter(line -> !line.startsWith("#") && !line.isBlank()).toList();
    }
    return lines.stream()
        .map(
            line ->
                DynamicTest.dynamicTest(
                    line,
                    () -> {
                      var parts = line.split("\t");
                      switch (parts[0]) {
                        case "type" -> {
                          var type = Class.forName(parts[1]);
                          assertTrue(Modifier.isPublic(type.getModifiers()), line);
                          switch (parts[2]) {
                            case "interface" -> assertTrue(type.isInterface(), line);
                            case "record" -> assertTrue(type.isRecord(), line);
                            case "enum" -> assertTrue(type.isEnum(), line);
                            case "abstract" ->
                                assertTrue(Modifier.isAbstract(type.getModifiers()), line);
                            case "class" ->
                                assertTrue(
                                    !type.isInterface()
                                        && !type.isEnum()
                                        && !type.isRecord()
                                        && !Modifier.isAbstract(type.getModifiers()),
                                    line);
                            default -> throw new AssertionError("Unknown kind: " + parts[2]);
                          }
                        }
                        case "component" -> {
                          var type = Class.forName(parts[1]);
                          assertTrue(type.isRecord(), line);
                          var components = Arrays.asList(type.getRecordComponents());
                          int index = Integer.parseInt(parts[2]);
                          assertTrue(index < components.size(), line);
                          var component = components.get(index);
                          assertEquals(parts[3], component.getName(), line);
                          assertEquals(parts[4], component.getGenericType().getTypeName(), line);
                        }
                        case "method" -> {
                          var type = Class.forName(parts[1]);
                          var parameterColumn = parts.length > 4 ? parts[4] : "";
                          var params =
                              parameterColumn.isEmpty()
                                  ? List.<String>of()
                                  : Arrays.asList(parameterColumn.split("\\|"));
                          Method method =
                              Stream.concat(
                                      Arrays.stream(type.getMethods()),
                                      Arrays.stream(type.getDeclaredMethods()))
                                  .filter(m -> m.getName().equals(parts[2]))
                                  .filter(
                                      m ->
                                          Modifier.isPublic(m.getModifiers())
                                              || Modifier.isProtected(m.getModifiers()))
                                  .filter(
                                      m ->
                                          Arrays.stream(m.getGenericParameterTypes())
                                              .map(java.lang.reflect.Type::getTypeName)
                                              .toList()
                                              .equals(params))
                                  .findFirst()
                                  .orElseThrow(
                                      () ->
                                          new AssertionError("Missing canonical method: " + line));
                          assertEquals(parts[3], method.getGenericReturnType().getTypeName(), line);
                        }
                        case "enum" -> {
                          var type = Class.forName(parts[1]);
                          assertEquals(
                              Arrays.asList(parts[2].split("\\|")),
                              Arrays.stream(type.getEnumConstants()).map(Object::toString).toList(),
                              line);
                        }
                        case "field" -> {
                          var field = Class.forName(parts[1]).getField(parts[2]);
                          assertTrue(Modifier.isPublic(field.getModifiers()), line);
                          assertTrue(Modifier.isFinal(field.getModifiers()), line);
                          assertEquals(parts[3], field.getGenericType().getTypeName(), line);
                        }
                        case "provider" -> {
                          assertTrue(
                              Class.forName(parts[1]).isAssignableFrom(Class.forName(parts[2])),
                              line);
                        }
                        case "sealed" -> {
                          var permitted =
                              Arrays.stream(Class.forName(parts[1]).getPermittedSubclasses())
                                  .map(Class::getName)
                                  .toList();
                          assertEquals(Arrays.asList(parts[2].split("\\|")), permitted, line);
                        }
                        case "helper" -> assertNotNull(Class.forName(parts[1]), line);
                        default -> throw new AssertionError("Unknown fixture row: " + line);
                      }
                    }));
  }

  @Test
  void fixtureHasCompleteCanonicalCoverageAndIndependentJavaTypeNames() throws Exception {
    var rows = fixtureRows();
    var canonicalTypes = rows.stream().filter(row -> row[0].equals("type")).toList();
    assertEquals(218, canonicalTypes.size());
    assertEquals(178, rows.stream().filter(row -> row[0].equals("component")).count());
    assertEquals(316, rows.stream().filter(row -> row[0].equals("method")).count());
    assertEquals(92, rows.stream().filter(row -> row[0].equals("provider")).count());

    assertCompiledTypeInventoryMatchesFixture(rows);

    for (var typeRow : canonicalTypes) {
      var className = typeRow[1];
      var type = Class.forName(className);
      if (type.isRecord()) {
        var expected =
            rows.stream()
                .filter(row -> row[0].equals("component") && row[1].equals(className))
                .map(row -> row[3] + ":" + row[4])
                .toList();
        assertEquals(
            expected,
            java.util.Arrays.stream(type.getRecordComponents())
                .map(
                    component ->
                        component.getName() + ":" + component.getGenericType().getTypeName())
                .toList(),
            className);
      }
    }

    for (var row : rows) {
      if (row[0].equals("method")) {
        assertJavaTypeName(row[3]);
        var parameterColumn = row.length > 4 ? row[4] : "";
        if (!parameterColumn.isEmpty()) {
          for (var parameter : parameterColumn.split("\\|")) assertJavaTypeName(parameter);
        }
      } else if (row[0].equals("component")) {
        assertJavaTypeName(row[4]);
      }
    }
  }

  @Test
  void legacyRootContractsAreAbsentAndDocumentedHelpersRemainInventoried() throws Exception {
    var rows = fixtureRows();
    for (var name :
        List.of(
            "ChangeRecord",
            "CheckpointResult",
            "CheckpointService",
            "CrashRecovery",
            "DbmsException",
            "ErrorCode",
            "GenerationManifest",
            "GenerationManifestStore",
            "HistoricalRecoveryService",
            "HistoricalRecoveryStorage",
            "ImmutableBytes",
            "LogFilter",
            "LogInspection",
            "LogSequenceNumber",
            "LogView",
            "MaintenanceGuard",
            "RecordId",
            "RecordImage",
            "RecoveryBoundary",
            "RecoveryCoordinator",
            "RecoveryHighWater",
            "RecoveryMutation",
            "RecoveryPageStore",
            "RecoveryResult",
            "RecoveryStage",
            "RecoveryStatus",
            "RecoveryTarget",
            "RollbackService",
            "SealedGeneration",
            "SnapshotMetadata",
            "StorageGenerationAdmin",
            "TestReport",
            "TestResult",
            "TransactionId",
            "TransactionWal",
            "WalDurability",
            "StoredRow")) {
      assertThrowsClassAbsent("dynamitedb.contracts." + name);
    }
    assertEquals(24, rows.stream().filter(row -> row[0].equals("helper")).count());
    assertEquals(5, rows.stream().filter(row -> row[0].equals("sealed")).count());
  }

  private static List<String[]> fixtureRows() throws Exception {
    try (var stream =
        CanonicalStructureTest.class.getResourceAsStream("/canonical-structure.tsv")) {
      assertNotNull(stream);
      try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        return reader
            .lines()
            .filter(line -> !line.startsWith("#") && !line.isBlank())
            .map(line -> line.split("\t"))
            .toList();
      }
    }
  }

  private static void assertCompiledTypeInventoryMatchesFixture(List<String[]> rows)
      throws Exception {
    var expected = new TreeSet<String>();
    rows.stream()
        .filter(row -> row[0].equals("type") || row[0].equals("helper"))
        .map(row -> row[1])
        .forEach(expected::add);

    var roots = new TreeSet<Path>();
    rows.stream()
        .filter(row -> row[0].equals("type"))
        .map(row -> classFor(row[1]))
        .filter(java.util.Objects::nonNull)
        .map(type -> type.getProtectionDomain().getCodeSource().getLocation())
        .map(
            location -> {
              try {
                return Path.of(location.toURI());
              } catch (java.net.URISyntaxException exception) {
                throw new IllegalStateException(exception);
              }
            })
        .forEach(roots::add);

    var actual = new TreeSet<String>();
    for (var root : roots) {
      if (Files.isDirectory(root)) {
        try (var paths = Files.walk(root)) {
          for (var classFile : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
            var relative = root.relativize(classFile).toString().replace('\\', '/');
            addNamedProductionType(actual, relative, CanonicalStructureTest.class.getClassLoader());
          }
        }
      } else {
        try (var jar = new JarFile(root.toFile())) {
          var entries = jar.entries();
          while (entries.hasMoreElements()) {
            addNamedProductionType(
                actual,
                entries.nextElement().getName(),
                CanonicalStructureTest.class.getClassLoader());
          }
        }
      }
    }
    assertEquals(expected, actual, "Canonical/helper inventory must cover named production types");
  }

  private static void addNamedProductionType(Set<String> inventory, String path, ClassLoader loader)
      throws ClassNotFoundException {
    if (!path.startsWith("dynamitedb/")
        || !path.endsWith(".class")
        || path.endsWith("package-info.class")) {
      return;
    }
    var binaryName = path.substring(0, path.length() - ".class".length()).replace('/', '.');
    var type = Class.forName(binaryName, false, loader);
    if (!type.isAnonymousClass() && !type.isLocalClass() && !type.isSynthetic()) {
      inventory.add(type.getName());
    }
  }

  private static void assertJavaTypeName(String name) {
    for (var container : List.of("List<", "Optional<", "Map<", "Set<", "Iterator<")) {
      assertTrue(!name.startsWith(container), "Unqualified fixture container type: " + name);
    }
    assertTrue(!name.matches(".*[,<] Long[>,].*"), "Unqualified boxed fixture type: " + name);
    assertTrue(!name.equals("Duration"), "Unqualified fixture type: " + name);
  }

  private static Class<?> classFor(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException exception) {
      return null;
    }
  }

  private static void assertThrowsClassAbsent(String name) {
    assertTrue(classFor(name) == null, "Legacy root contract still exists: " + name);
  }
}
