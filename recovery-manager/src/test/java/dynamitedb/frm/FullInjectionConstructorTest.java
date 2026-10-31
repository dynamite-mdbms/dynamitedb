package dynamitedb.frm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.frm.mock.MockRecoveryContext;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FullInjectionConstructorTest {
  @Test
  void acceptsAllHistoricalRecoveryCollaborators() {
    assertTrue(
        Arrays.stream(HistoricalRecoveryManager.class.getConstructors())
            .anyMatch(constructor -> constructor.getParameterCount() == 7));
  }

  @Test
  void fullInjectionConstructorRejectsMissingCollaborators() {
    MockRecoveryContext context = new MockRecoveryContext();
    HistoryCatalog catalog = new HistoryCatalog(context);

    assertThrows(
        NullPointerException.class,
        () -> new HistoricalRecoveryManager(null, catalog, null, null, null, null, null, context));
  }
}
