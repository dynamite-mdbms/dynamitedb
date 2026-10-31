package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Checks the independent supplied diagram signatures, not signatures generated from Java code. */
class CanonicalContractTest {
  @TestFactory
  Stream<DynamicTest> publicApiMatchesSuppliedDiagrams() throws Exception {
    var stream = getClass().getResourceAsStream("/frm-canonical-signatures.tsv");
    assertNotNull(stream);
    List<String> lines;
    try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      lines = reader.lines().filter(s -> !s.startsWith("#")).toList();
    }
    return lines.stream()
        .map(
            line ->
                DynamicTest.dynamicTest(
                    line,
                    () -> {
                      var parts = line.split("\t", -1);
                      var type = Class.forName(parts[1]);
                      switch (parts[0]) {
                        case "type" -> {
                          assertTrue(Modifier.isPublic(type.getModifiers()));
                          switch (parts[2]) {
                            case "interface" -> assertTrue(type.isInterface());
                            case "record" -> assertTrue(type.isRecord());
                            case "enum" -> assertTrue(type.isEnum());
                            case "abstract" -> assertTrue(Modifier.isAbstract(type.getModifiers()));
                            default ->
                                assertTrue(
                                    !type.isInterface() && !type.isRecord() && !type.isEnum());
                          }
                        }
                        case "method" -> {
                          var params =
                              parts[4].isEmpty()
                                  ? List.<String>of()
                                  : Arrays.asList(parts[4].split("\\|"));
                          Method method =
                              Arrays.stream(type.getMethods())
                                  .filter(
                                      m ->
                                          m.getName().equals(parts[2])
                                              && Arrays.stream(m.getGenericParameterTypes())
                                                  .map(java.lang.reflect.Type::getTypeName)
                                                  .toList()
                                                  .equals(params))
                                  .findFirst()
                                  .orElseThrow(
                                      () ->
                                          new AssertionError("Missing canonical method: " + line));
                          assertEquals(parts[3], method.getGenericReturnType().getTypeName());
                        }
                        case "component" -> {
                          var component =
                              Arrays.stream(type.getRecordComponents())
                                  .filter(c -> c.getName().equals(parts[2]))
                                  .findFirst()
                                  .orElseThrow();
                          assertEquals(parts[3], component.getGenericType().getTypeName());
                        }
                        case "field" -> {
                          var field = type.getField(parts[2]);
                          assertEquals(parts[3], field.getGenericType().getTypeName());
                          assertTrue(Modifier.isFinal(field.getModifiers()));
                        }
                        case "enum" ->
                            assertEquals(
                                Arrays.asList(parts[2].split("\\|")),
                                Arrays.stream(type.getEnumConstants())
                                    .map(Object::toString)
                                    .toList());
                        default -> throw new AssertionError("Unknown signature row");
                      }
                    }));
  }
}
