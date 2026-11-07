package dynamitedb.sm.heap;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.sm.buffer.BufferPool;
import dynamitedb.sm.buffer.Page;
import dynamitedb.sm.catalog.CatalogManager;
import dynamitedb.sm.serialization.RecordSerializer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.CRC32;

/** Heap page access dan slot allocation; table-to-page mapping disimpan di heap.dir. */
public final class HeapFile {
  private static final int MAGIC = 0x44594844;
  private static final int VERSION = 1;
  private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

  private final Path file;
  private final BufferPool buffer;
  private final RecordSerializer serializer;
  private final CatalogManager catalog;

  private final Map<Long, List<Long>> pagesByTable = new HashMap<>();
  private final Map<Long, Long> ownerByPage = new HashMap<>();
  private final Map<Long, Map<Integer, RecordId>> reservedIds = new HashMap<>();
  private final Map<Long, Map<Integer, RecordImage>> reservedImages = new HashMap<>();
  private final Map<Long, Integer> freeHint = new HashMap<>();
  private boolean loaded;

  public HeapFile(
      Path root, BufferPool buffer, RecordSerializer serializer, CatalogManager catalog) {
    this.file =
        Objects.requireNonNull(root, "root").toAbsolutePath().normalize().resolve("heap.dir");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  /** Pin heap page; caller wajib unpin. */
  public synchronized Page readPage(long pageId) {
    ensureLoaded();
    if (!ownerByPage.containsKey(pageId)) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Bukan heap page: " + pageId);
    }
    return buffer.pin(pageId);
  }

  /** Reserve slot seukuran widest row; release dengan releaseSlot. */
  public synchronized RecordId allocateSlot(String table) {
    Schema schema = catalog.getSchema(table);
    return allocateSlot(table, serializer.encode(widestRow(schema), schema));
  }

  /** Reserve slot untuk image, append page baru bila tidak ada yang muat. */
  public synchronized RecordId allocateSlot(String table, RecordImage image) {
    ensureLoaded();
    requireLive(image);
    long tableId = catalog.getTableId(table);
    int size = image.encoded().copy().length;
    if (size > RecordSerializer.MAX_RECORD_SIZE) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Row " + size + " byte melebihi page limit " + RecordSerializer.MAX_RECORD_SIZE);
    }
    for (long pageId : insertCandidates(tableId, size)) {
      RecordId rid = tryReserve(tableId, pageId, image, size);
      if (rid != null) {
        return rid;
      }
    }
    long pageId = appendPage(table, tableId);
    RecordId rid = tryReserve(tableId, pageId, image, size);
    if (rid == null) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Row tidak muat di empty page: " + table);
    }
    return rid;
  }

  /** Reserve space untuk in-place update; RID tidak pernah pindah. */
  public synchronized void reserveUpdate(RecordId rid, RecordImage after) {
    ensureLoaded();
    requireLive(after);
    requireOwned(rid);
    if (reservedIds.getOrDefault(rid.pageNumber(), Map.of()).containsKey(rid.slotNumber())) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Slot sedang di-reserve: " + rid);
    }
    Page page = buffer.pin(rid.pageNumber());
    try {
      ImmutableBytes projected = project(rid.pageNumber(), page.getData());
      try {
        serializer.writeSlot(projected, rid.slotNumber(), rid.generation(), after);
      } catch (DbmsException e) {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE,
            "Updated row tidak muat di page-nya, RID tidak boleh pindah: " + rid,
            e);
      }
      reserve(rid, after);
    } finally {
      buffer.unpin(page);
    }
  }

  /** Release slot reservation (idempotent). */
  public synchronized void releaseSlot(RecordId rid) {
    Objects.requireNonNull(rid, "rid");
    var ids = reservedIds.get(rid.pageNumber());
    if (ids == null || !rid.equals(ids.get(rid.slotNumber()))) {
      return;
    }
    ids.remove(rid.slotNumber());
    reservedImages.get(rid.pageNumber()).remove(rid.slotNumber());
    if (ids.isEmpty()) {
      reservedIds.remove(rid.pageNumber());
      reservedImages.remove(rid.pageNumber());
    }
  }

  /** Flush page lewat BufferPool (WAL gate dulu). */
  public synchronized void writePage(Page page) {
    requireOwnedPage(Objects.requireNonNull(page, "page").getId());
    buffer.flush(page);
  }

  /** Read row image; absent bila slot dead atau incarnation beda. */
  public RecordImage read(RecordId rid) {
    Objects.requireNonNull(rid, "rid");
    synchronized (this) {
      ensureLoaded();
      Long owner = ownerByPage.get(rid.pageNumber());
      if (owner == null || owner != rid.tableId()) {
        return RecordImage.absent();
      }
    }
    Page page = buffer.pin(rid.pageNumber());
    try {
      return serializer.readSlot(page.getData(), rid.slotNumber(), rid.generation());
    } finally {
      buffer.unpin(page);
    }
  }

  /** RID semua live row milik table, untuk index build. */
  public List<RecordId> recordIds(String table) {
    long tableId = catalog.getTableId(table);
    var result = new ArrayList<RecordId>();
    for (long pageId : pageIds(tableId)) {
      Page page = buffer.pin(pageId);
      ImmutableBytes data;
      try {
        data = page.getData();
      } finally {
        buffer.unpin(page);
      }
      int slots = serializer.getSlotCount(data);
      for (int slot = 0; slot < slots; slot++) {
        long version = serializer.getSlotVersion(data, slot);
        if (serializer.readSlot(data, slot, version).exists()) {
          result.add(new RecordId(tableId, pageId, slot, version));
        }
      }
    }
    return List.copyOf(result);
  }

  /** Page iterator milik table; tiap next() pin satu page, caller unpin. */
  public Iterator<Page> pages(String table) {
    List<Long> ids = pageIds(catalog.getTableId(table));
    return new Iterator<>() {
      private int position;

      @Override
      public boolean hasNext() {
        return position < ids.size();
      }

      @Override
      public Page next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return buffer.pin(ids.get(position++));
      }
    };
  }

  /** Jumlah page milik table (b_r). */
  public synchronized long pageCount(String table) {
    return pageIds(catalog.getTableId(table)).size();
  }

  /** Catat page sebagai milik table, dipakai recovery. */
  public synchronized void adopt(long tableId, long pageId) {
    ensureLoaded();
    Long owner = ownerByPage.get(pageId);
    if (owner != null) {
      if (owner != tableId) {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE,
            "Page " + pageId + " milik table " + owner + ", bukan " + tableId);
      }
      return;
    }
    ownerByPage.put(pageId, tableId);
    pagesByTable.computeIfAbsent(tableId, id -> new ArrayList<>()).add(pageId);
    persist();
  }

  /** Update free-space hint setelah page berubah. */
  public synchronized void noteFreeSpace(long pageId, ImmutableBytes data) {
    freeHint.put(pageId, serializer.getFreeSpaceSize(project(pageId, data)));
  }

  /** Reload heap.dir dan buang reservation, dipakai saat generation switch. */
  public synchronized void reload() {
    pagesByTable.clear();
    ownerByPage.clear();
    reservedIds.clear();
    reservedImages.clear();
    freeHint.clear();
    loaded = false;
    ensureLoaded();
  }

  private synchronized List<Long> pageIds(long tableId) {
    ensureLoaded();
    return List.copyOf(pagesByTable.getOrDefault(tableId, List.of()));
  }

  private List<Long> insertCandidates(long tableId, int size) {
    List<Long> pages = pagesByTable.getOrDefault(tableId, List.of());
    var candidates = new ArrayList<Long>();
    int needed = size + RecordSerializer.SLOT_SIZE;
    for (long pageId : pages) {
      if (freeHint.getOrDefault(pageId, -1) >= needed) {
        candidates.add(pageId);
      }
    }
    if (!pages.isEmpty()) {
      long last = pages.get(pages.size() - 1);
      if (!candidates.contains(last)) {
        candidates.add(last);
      }
    }
    return candidates;
  }

  private RecordId tryReserve(long tableId, long pageId, RecordImage image, int size) {
    Page page = buffer.pin(pageId);
    try {
      ImmutableBytes projected = project(pageId, page.getData());
      int slot = serializer.findSlotForAllocation(projected, size);
      if (slot < 0) {
        freeHint.put(pageId, serializer.getFreeSpaceSize(projected));
        return null;
      }
      long version = serializer.nextSlotVersion(projected, slot);
      RecordId rid = new RecordId(tableId, pageId, slot, version);
      reserve(rid, image);
      freeHint.put(
          pageId,
          serializer.getFreeSpaceSize(serializer.writeSlot(projected, slot, version, image)));
      return rid;
    } finally {
      buffer.unpin(page);
    }
  }

  private long appendPage(String table, long tableId) {
    Page page = buffer.allocatePage();
    long pageId = page.getId();
    try {
      ownerByPage.put(pageId, tableId);
      pagesByTable.computeIfAbsent(tableId, id -> new ArrayList<>()).add(pageId);
      try {
        persist();
      } catch (RuntimeException e) {
        // Orphan page di disk aman selama tidak tercatat di heap.dir.
        ownerByPage.remove(pageId);
        pagesByTable.get(tableId).remove(Long.valueOf(pageId));
        throw e;
      }
      freeHint.put(pageId, serializer.getFreeSpaceSize(page.getData()));
    } finally {
      buffer.unpin(page);
    }
    catalog.updateBlockCount(table, pagesByTable.get(tableId).size());
    return pageId;
  }

  private void reserve(RecordId rid, RecordImage image) {
    reservedIds.computeIfAbsent(rid.pageNumber(), id -> new HashMap<>()).put(rid.slotNumber(), rid);
    reservedImages
        .computeIfAbsent(rid.pageNumber(), id -> new HashMap<>())
        .put(rid.slotNumber(), image);
  }

  private ImmutableBytes project(long pageId, ImmutableBytes data) {
    var ids = reservedIds.get(pageId);
    if (ids == null) {
      return data;
    }
    ImmutableBytes projected = data;
    var images = reservedImages.get(pageId);
    for (var entry : new TreeMap<>(ids).entrySet()) {
      RecordId rid = entry.getValue();
      projected =
          serializer.writeSlot(
              projected, rid.slotNumber(), rid.generation(), images.get(entry.getKey()));
    }
    return projected;
  }

  private void requireOwned(RecordId rid) {
    Long owner = ownerByPage.get(rid.pageNumber());
    if (owner == null || owner != rid.tableId()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "RID bukan milik table-nya: " + rid);
    }
  }

  private void requireOwnedPage(long pageId) {
    ensureLoaded();
    if (!ownerByPage.containsKey(pageId)) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Bukan heap page: " + pageId);
    }
  }

  private static void requireLive(RecordImage image) {
    if (!Objects.requireNonNull(image, "image").exists()) {
      throw new DbmsException(ErrorCode.INVALID_STATE, "Slot reservation butuh existing image");
    }
  }

  private static Row widestRow(Schema schema) {
    var values = new ArrayList<Value>();
    for (Column column : schema.columns()) {
      values.add(
          switch (column.dataType()) {
            case INTEGER -> new IntValue(0);
            case FLOAT -> new FloatValue(0);
            case CHAR, VARCHAR -> new TextValue("x".repeat(Math.max(0, column.maxLength())));
          });
    }
    return new Row(values);
  }

  private void ensureLoaded() {
    if (loaded) {
      return;
    }
    if (Files.exists(file)) {
      load();
    }
    loaded = true;
  }

  private void load() {
    try {
      long size = Files.size(file);
      if (size < 20 || size > MAX_FILE_BYTES) {
        throw failure("Size heap.dir tidak valid");
      }
      byte[] bytes = Files.readAllBytes(file);
      int payload = bytes.length - Long.BYTES;
      var crc = new CRC32();
      crc.update(bytes, 0, payload);
      if (ByteBuffer.wrap(bytes).getLong(payload) != crc.getValue()) {
        throw failure("Checksum heap.dir tidak cocok");
      }
      try (var in = new DataInputStream(new ByteArrayInputStream(bytes, 0, payload))) {
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
          throw failure("Format heap.dir tidak dikenal");
        }
        int tables = in.readInt();
        if (tables < 0) {
          throw failure("Table count heap.dir negatif");
        }
        for (int t = 0; t < tables; t++) {
          long tableId = in.readLong();
          int count = in.readInt();
          if (count < 0 || pagesByTable.containsKey(tableId)) {
            throw failure("Table entry heap.dir rusak");
          }
          var pages = new ArrayList<Long>(count);
          for (int p = 0; p < count; p++) {
            long pageId = in.readLong();
            if (pageId < 0 || ownerByPage.putIfAbsent(pageId, tableId) != null) {
              throw failure("Page heap.dir rusak atau duplikat: " + pageId);
            }
            pages.add(pageId);
          }
          pagesByTable.put(tableId, pages);
        }
        if (in.available() != 0) {
          throw failure("Trailing bytes di heap.dir");
        }
      }
    } catch (IOException e) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Tidak bisa read " + file, e);
    }
  }

  private void persist() {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(pagesByTable.size());
        for (var entry : new TreeMap<>(pagesByTable).entrySet()) {
          out.writeLong(entry.getKey());
          out.writeInt(entry.getValue().size());
          for (long pageId : entry.getValue()) {
            out.writeLong(pageId);
          }
        }
      }
      byte[] payload = bytes.toByteArray();
      var crc = new CRC32();
      crc.update(payload);
      var content = ByteBuffer.allocate(payload.length + Long.BYTES);
      content.put(payload).putLong(crc.getValue()).flip();

      Files.createDirectories(file.getParent());
      Path temp = file.resolveSibling("heap.dir.tmp");
      try (var channel =
          FileChannel.open(
              temp,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        while (content.hasRemaining()) {
          channel.write(content);
        }
        channel.force(true);
      }
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Tidak bisa write " + file, e);
    }
  }

  private static DbmsException failure(String message) {
    return new DbmsException(ErrorCode.STORAGE_FAILURE, message);
  }
}
