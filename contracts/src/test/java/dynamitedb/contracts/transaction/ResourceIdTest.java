package dynamitedb.contracts.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.storage.RecordId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResourceIdTest {
  @Test
  void rejectsRecordIdFromDifferentTable() {
    RecordId recordId = new RecordId(2L, 3L, 4, 5L);

    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceId(ResourceKind.RECORD, 1L, Optional.of(recordId)));
  }

  @Test
  void validatesResourceShapeAndPreservesFactoryValues() {
    RecordId recordId = new RecordId(2L, 1L, 0, 7L);
    ResourceId table = ResourceId.table(2L);
    ResourceId record = ResourceId.record(recordId);

    assertEquals(ResourceKind.TABLE, table.kind());
    assertEquals(Optional.empty(), table.recordId());
    assertEquals(2L, table.tableId());
    assertEquals(ResourceKind.RECORD, record.kind());
    assertSame(recordId, record.recordId().orElseThrow());
    assertEquals(recordId.tableId(), record.tableId());
    assertThrows(NullPointerException.class, () -> new ResourceId(null, 1L, Optional.empty()));
    assertThrows(NullPointerException.class, () -> new ResourceId(ResourceKind.TABLE, 1L, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceId(ResourceKind.TABLE, 1L, Optional.of(recordId)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceId(ResourceKind.RECORD, 1L, Optional.empty()));
    assertThrows(NullPointerException.class, () -> ResourceId.record(null));
  }
}
