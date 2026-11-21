package dynamitedb.sm.serialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.sm.buffer.DiskManager;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverlappingSlotTest {
  private static final Schema INT =
      new Schema(List.of(new Column("t", "v", DataType.INTEGER, 0, false)));
  private static final int DISK_HEADER = 16;

  @TempDir Path directory;

  private final RecordSerializer serializer = new RecordSerializer();

  @Test
  void persistedPageWithOverlappingLiveSlotsIsRejectedAfterReload() throws Exception {
    ImmutableBytes page = ImmutableBytes.copyOf(new byte[RecordSerializer.PAGE_SIZE]);
    page = serializer.writeSlot(page, 0, 0, serializer.encode(row(11), INT));
    page = serializer.writeSlot(page, 1, 0, serializer.encode(row(22), INT));
    long pageId = new DiskManager(directory).appendPage(page);

    // Ganti offset slot 1 dengan offset slot 0 langsung di pages.db (header, size, pageLSN tetap).
    long base = pageId * (DISK_HEADER + RecordSerializer.PAGE_SIZE) + DISK_HEADER;
    int slot0Offset = RecordSerializer.PAGE_HEADER_SIZE + 1;
    int slot1Offset = RecordSerializer.PAGE_HEADER_SIZE + RecordSerializer.SLOT_SIZE + 1;
    try (var file = new RandomAccessFile(directory.resolve("pages.db").toFile(), "rw")) {
      byte[] offset = new byte[2];
      file.seek(base + slot0Offset);
      file.readFully(offset);
      file.seek(base + slot1Offset);
      file.write(offset);
      file.getFD().sync();
    }

    ImmutableBytes reloaded = new DiskManager(directory).readPage(pageId);
    for (int slot : List.of(0, 1)) {
      var error = assertThrows(DbmsException.class, () -> serializer.readSlot(reloaded, slot, 0));
      assertEquals(ErrorCode.STORAGE_FAILURE, error.getCode());
    }
  }

  @Test
  void multiRecordPagesDeletesCompactionAndSlotReuseStillValidate() {
    var schema = new Schema(List.of(new Column("t", "s", DataType.VARCHAR, 200, false)));
    ImmutableBytes page = ImmutableBytes.copyOf(new byte[RecordSerializer.PAGE_SIZE]);
    var versions = new ArrayList<Long>();
    for (int slot = 0; slot < 12; slot++) {
      page = serializer.writeSlot(page, slot, 0, serializer.encode(text(slot), schema));
      versions.add(0L);
    }
    for (int slot = 0; slot < 12; slot += 3) {
      page = serializer.deleteSlot(page, slot, 0);
    }
    page = serializer.compactPage(page);
    for (int slot = 0; slot < 12; slot += 3) {
      long version = serializer.nextSlotVersion(page, slot);
      page = serializer.writeSlot(page, slot, version, serializer.encode(text(100 + slot), schema));
      versions.set(slot, version);
    }
    for (int slot = 0; slot < 12; slot++) {
      int expected = slot % 3 == 0 ? 100 + slot : slot;
      RecordImage image = serializer.readSlot(page, slot, versions.get(slot));
      assertEquals(text(expected), serializer.decode(image, schema));
    }
  }

  private static Row row(int value) {
    return new Row(List.of(new IntValue(value)));
  }

  private static Row text(int value) {
    return new Row(List.of(new TextValue("row-" + value + "-" + "x".repeat(value % 40))));
  }
}
