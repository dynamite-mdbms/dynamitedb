package dynamitedb.sm.serialization;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.sm.buffer.DiskManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Konversi baris ke image dan sebaliknya untuk keempat tipe data (SM-02, SM-03). */
public final class RecordSerializer {
  public static final byte FORMAT_VERSION = 1;

  public RecordSerializer() {}

  /**
   * Encodes a row into binary (RecordImage).
   *
   * @param row row to encode
   * @param schema table schema
   * @return encoded record image with exists = true
   * @throws DbmsException if row values do not conform to schema
   */
  public RecordImage encode(Row row, Schema schema) {
    if (row == null || schema == null) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Row and schema must not be null");
    }

    List<Column> columns = schema.columns();
    List<Value> values = row.values();
    if (values.size() != columns.size()) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Row column count ("
              + values.size()
              + ") does not match schema ("
              + columns.size()
              + ")");
    }

    int colCount = columns.size();
    int nullBitmapBytes = (colCount + 7) / 8;
    byte[] nullBitmap = new byte[nullBitmapBytes];
    ByteArrayOutputStream payload = new ByteArrayOutputStream();

    for (int i = 0; i < colCount; i++) {
      Column column = columns.get(i);
      Value value = values.get(i);
      if (value == null) {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE,
            "Column '" + column.name() + "' value must not be java's null; use NullValue.INSTANCE");
      }

      if (value instanceof NullValue) {
        if (!column.nullable()) {
          throw new DbmsException(
              ErrorCode.STORAGE_FAILURE, "Column '" + column.name() + "' is not nullable");
        }
        nullBitmap[i / 8] |= (byte) (1 << (i % 8));
        continue;
      }

      switch (column.dataType()) {
        case INTEGER -> {
          if (!(value instanceof IntValue intVal)) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Type mismatch for column '"
                    + column.name()
                    + "': expected INTEGER, got "
                    + value.getClass().getSimpleName());
          }
          ByteBuffer buf = ByteBuffer.allocate(4);
          buf.putInt(intVal.value());
          payload.writeBytes(buf.array());
        }
        case FLOAT -> {
          if (!(value instanceof FloatValue floatVal)) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Type mismatch for column '"
                    + column.name()
                    + "': expected FLOAT, got "
                    + value.getClass().getSimpleName());
          }
          ByteBuffer buf = ByteBuffer.allocate(8);
          buf.putDouble(floatVal.value());
          payload.writeBytes(buf.array());
        }
        case CHAR -> {
          if (!(value instanceof TextValue textVal)) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Type mismatch for column '"
                    + column.name()
                    + "': expected CHAR, got "
                    + value.getClass().getSimpleName());
          }
          String s = textVal.value();
          if (s == null) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE, "TextValue for column '" + column.name() + "' is null");
          }
          // CHAR dipadding byte 0x00 dan decode berhenti di 0x00 pertama, jadi NUL ditolak
          if (s.indexOf('\0') >= 0) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "CHAR value for column '" + column.name() + "' contains a NUL character");
          }
          byte[] textBytes = s.getBytes(StandardCharsets.UTF_8);
          int maxLen = column.maxLength();
          if (maxLen < 0) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Invalid CHAR maxLength for column '" + column.name() + "': " + maxLen);
          }
          if (textBytes.length > maxLen) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Text length ("
                    + textBytes.length
                    + " bytes) exceeds CHAR("
                    + maxLen
                    + ") on column '"
                    + column.name()
                    + "'");
          }
          byte[] padded = new byte[maxLen];
          System.arraycopy(textBytes, 0, padded, 0, textBytes.length);
          payload.writeBytes(padded);
        }
        case VARCHAR -> {
          if (!(value instanceof TextValue textVal)) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Type mismatch for column '"
                    + column.name()
                    + "': expected VARCHAR, got "
                    + value.getClass().getSimpleName());
          }
          String s = textVal.value();
          if (s == null) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE, "TextValue for column '" + column.name() + "' is null");
          }
          byte[] textBytes = s.getBytes(StandardCharsets.UTF_8);
          int maxLen = column.maxLength();
          if (maxLen < 0) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Invalid VARCHAR maxLength for column '" + column.name() + "': " + maxLen);
          }
          if (textBytes.length > maxLen || textBytes.length > 0xFFFF) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Text length ("
                    + textBytes.length
                    + " bytes) exceeds VARCHAR("
                    + maxLen
                    + ") on column '"
                    + column.name()
                    + "'");
          }
          ByteBuffer buf = ByteBuffer.allocate(Short.BYTES + textBytes.length);
          buf.putShort((short) textBytes.length);
          buf.put(textBytes);
          payload.writeBytes(buf.array());
        }
      }
    }

    byte[] payloadBytes = payload.toByteArray();
    ByteBuffer fullImage = ByteBuffer.allocate(1 + nullBitmapBytes + payloadBytes.length);
    fullImage.put(FORMAT_VERSION);
    fullImage.put(nullBitmap);
    fullImage.put(payloadBytes);

    return new RecordImage(true, ImmutableBytes.copyOf(fullImage.array()));
  }

  /**
   * Decodes binary (RecordImage) into a row.
   *
   * @param image binary record image
   * @param schema table schema
   * @return decoded relational row
   * @throws DbmsException if image is absent, corrupt, or truncated
   */
  public Row decode(RecordImage image, Schema schema) {
    if (image == null || schema == null) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Image and schema must not be null");
    }

    if (!image.exists()) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Cannot decode absent RecordImage");
    }

    byte[] bytes = image.encoded().copy();
    if (bytes.length < 1) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Record image data is empty or truncated");
    }

    int version = bytes[0];
    if (version != FORMAT_VERSION) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Unsupported record format version: " + version + ", expected: " + FORMAT_VERSION);
    }

    List<Column> columns = schema.columns();
    int colCount = columns.size();
    int nullBitmapBytes = (colCount + 7) / 8;
    if (1 + nullBitmapBytes > bytes.length) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Record image truncated: missing null bitmap");
    }

    ByteBuffer buf = ByteBuffer.wrap(bytes);
    buf.position(1);
    byte[] nullBitmap = new byte[nullBitmapBytes];
    buf.get(nullBitmap);

    List<Value> values = new ArrayList<>(colCount);
    for (int i = 0; i < colCount; i++) {
      Column column = columns.get(i);
      boolean isNull = (nullBitmap[i / 8] & (1 << (i % 8))) != 0;

      if (isNull) {
        if (!column.nullable()) {
          throw new DbmsException(
              ErrorCode.STORAGE_FAILURE,
              "Non-nullable column '" + column.name() + "' is marked null in record image");
        }
        values.add(NullValue.INSTANCE);
        continue;
      }

      switch (column.dataType()) {
        case INTEGER -> {
          if (buf.remaining() < 4) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Truncated INTEGER value on column '" + column.name() + "'");
          }
          values.add(new IntValue(buf.getInt()));
        }
        case FLOAT -> {
          if (buf.remaining() < 8) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Truncated FLOAT value on column '" + column.name() + "'");
          }
          values.add(new FloatValue(buf.getDouble()));
        }
        case CHAR -> {
          int len = column.maxLength();
          if (len < 0 || buf.remaining() < len) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Truncated CHAR value on column '" + column.name() + "'");
          }
          byte[] charBytes = new byte[len];
          buf.get(charBytes);
          int actualLen = len;
          for (int j = 0; j < len; j++) {
            if (charBytes[j] == 0) {
              actualLen = j;
              break;
            }
          }
          String s = new String(charBytes, 0, actualLen, StandardCharsets.UTF_8);
          values.add(new TextValue(s));
        }
        case VARCHAR -> {
          if (buf.remaining() < 2) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "Truncated VARCHAR length prefix on column '" + column.name() + "'");
          }
          int varLen = Short.toUnsignedInt(buf.getShort());
          int maxLen = column.maxLength();
          if (maxLen < 0 || varLen > maxLen) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "VARCHAR length ("
                    + varLen
                    + ") exceeds schema maxLength ("
                    + maxLen
                    + ") on column '"
                    + column.name()
                    + "'");
          }
          if (buf.remaining() < varLen) {
            throw new DbmsException(
                ErrorCode.STORAGE_FAILURE,
                "truncated VARCHAR value on column '" + column.name() + "'");
          }
          byte[] varcharBytes = new byte[varLen];
          buf.get(varcharBytes);
          String s = new String(varcharBytes, StandardCharsets.UTF_8);
          values.add(new TextValue(s));
        }
      }
    }

    if (buf.hasRemaining()) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Corrupted record image: " + buf.remaining() + " unread trailing bytes");
    }

    return new Row(values);
  }

  // Slotted Page operations

  public static final int PAGE_SIZE = DiskManager.PAGE_SIZE;
  public static final int PAGE_HEADER_SIZE = 8;
  public static final int SLOT_SIZE = 13;
  public static final int MAX_RECORD_SIZE = PAGE_SIZE - PAGE_HEADER_SIZE - SLOT_SIZE;
  public static final byte SLOT_EMPTY = 0;
  public static final byte SLOT_LIVE = 1;
  private static final short PAGE_MAGIC = (short) 0x6767;

  /**
   * Gets the total number of slots in the page slot directory.
   *
   * @param pageData binary page data
   * @return number of slots in the directory
   * @throws DbmsException if page data is null or corrupted
   */
  public int getSlotCount(ImmutableBytes pageData) {
    ByteBuffer buf = validateAndWrapPage(pageData);
    return Short.toUnsignedInt(buf.getShort(4));
  }

  /**
   * Gets the slot version (reuse counter) of a specific slot. (panggil ini untuk menjamin bahwa
   * slot memang masih dipakai untuk suatu row dan belum berubah)
   *
   * @param pageData binary page data
   * @param slotNumber slot index to inspect
   * @return current slot version (reuse counter)
   * @throws DbmsException if page data is corrupted or slotNumber is out of bounds
   */
  public long getSlotVersion(ImmutableBytes pageData, int slotNumber) {
    ByteBuffer buf = validateAndWrapPage(pageData);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    requireValidSlotIndex(slotNumber, slotCount);
    return buf.getLong(slotEntryOffset(slotNumber) + 5);
  }

  /**
   * Gets the total available free space size in bytes in the page (including fragmented gaps
   * reclaimable via compaction).
   *
   * @param pageData binary page data
   * @return total available free space size in bytes
   * @throws DbmsException if page data is null or corrupted
   */
  public int getFreeSpaceSize(ImmutableBytes pageData) {
    ByteBuffer buf = validateAndWrapPage(pageData);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    int usedRecordSize = 0;
    for (int i = 0; i < slotCount; i++) {
      int pos = slotEntryOffset(i);
      if (buf.get(pos) == SLOT_LIVE) {
        usedRecordSize += Short.toUnsignedInt(buf.getShort(pos + 3));
      }
    }
    return PAGE_SIZE - directoryEndOffset(slotCount) - usedRecordSize;
  }

  /**
   * Checks whether a new record of the given byte size can fit into the page.
   *
   * @param pageData binary page data
   * @param recordSize size in bytes of the record to insert
   * @return true if the record can fit in the page, false otherwise
   * @throws DbmsException if page data is null or corrupted
   */
  public boolean canFit(ImmutableBytes pageData, int recordSize) {
    if (recordSize <= 0 || recordSize > MAX_RECORD_SIZE) {
      return false;
    }
    ByteBuffer buf = validateAndWrapPage(pageData);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    boolean hasReusableSlot = false;
    int usedRecordSize = 0;
    for (int i = 0; i < slotCount; i++) {
      int pos = slotEntryOffset(i);
      byte status = buf.get(pos);
      if (status == SLOT_EMPTY) {
        hasReusableSlot = true;
      } else if (status == SLOT_LIVE) {
        usedRecordSize += Short.toUnsignedInt(buf.getShort(pos + 3));
      }
    }
    int newSlotCount = hasReusableSlot ? slotCount : slotCount + 1;
    int totalNeededSize = directoryEndOffset(newSlotCount) + usedRecordSize + recordSize;
    return totalNeededSize <= PAGE_SIZE;
  }

  /**
   * Finds the slot index to use for allocating a record of the given byte size, dan mengembalikan
   * versi slot berikutnya lewat nextSlotVersion.
   *
   * @param pageData binary page data
   * @param recordSize size in bytes of the record to allocate
   * @return reusable empty slot index, slotCount for a new slot, or -1 if the page is full
   * @throws DbmsException if page data is null or corrupted
   */
  public int findSlotForAllocation(ImmutableBytes pageData, int recordSize) {
    if (!canFit(pageData, recordSize)) {
      return -1;
    }
    ByteBuffer buf = validateAndWrapPage(pageData);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    for (int i = 0; i < slotCount; i++) {
      if (buf.get(slotEntryOffset(i)) == SLOT_EMPTY) {
        return i;
      }
    }
    return slotCount;
  }

  /**
   * Computes the next slot version (reuse counter) when allocating a slot for a new record. If the
   * slot is brand-new at slotCount, its version is 0; if reusing a previously deleted slot, its
   * prior version is incremented by 1 so old RecordIds pointing to the previous record become
   * invalid.
   *
   * @param pageData binary page data
   * @param slotNumber slot index to allocate
   * @return 0 for a brand-new slot, or previousSlotVersion + 1 when reusing a deleted slot
   * @throws DbmsException if page data is corrupted or the slot is not empty
   */
  public long nextSlotVersion(ImmutableBytes pageData, int slotNumber) {
    ByteBuffer buf = validateAndWrapPage(pageData);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    if (slotNumber < 0 || slotNumber > slotCount) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Invalid slotNumber for allocation: " + slotNumber + " (slotCount=" + slotCount + ")");
    }
    if (slotNumber == slotCount) {
      return 0L;
    }
    int pos = slotEntryOffset(slotNumber);
    if (buf.get(pos) != SLOT_EMPTY) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Slot " + slotNumber + " is not empty and cannot be allocated");
    }
    long previousVersion = buf.getLong(pos + 5);
    if (previousVersion == Long.MAX_VALUE) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Slot version overflow at slot " + slotNumber);
    }
    return previousVersion + 1L;
  }

  /**
   * Reads a live record image from a slot only if its slot version matches expectedSlotVersion. If
   * the slot was deleted and reused for another record (causing its slot version to increase), this
   * returns an absent RecordImage so stale RecordIds do not read the wrong record.
   *
   * @param pageData binary page data
   * @param slotNumber slot index to read
   * @param expectedSlotVersion expected slot version from RecordId.generation
   * @return record image with exists = true if live and version matches, or absent image otherwise
   * @throws DbmsException if page data is null or corrupted
   */
  public RecordImage readSlot(ImmutableBytes pageData, int slotNumber, long expectedSlotVersion) {
    ByteBuffer buf = validateAndWrapPage(pageData);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    if (slotNumber < 0 || slotNumber >= slotCount) {
      return RecordImage.absent();
    }
    int pos = slotEntryOffset(slotNumber);
    byte status = buf.get(pos);
    long slotVersion = buf.getLong(pos + 5);
    if (status != SLOT_LIVE || slotVersion != expectedSlotVersion) {
      return RecordImage.absent();
    }
    int recordOffset = Short.toUnsignedInt(buf.getShort(pos + 1));
    int recordSize = Short.toUnsignedInt(buf.getShort(pos + 3));
    byte[] recordBytes = new byte[recordSize];
    buf.position(recordOffset);
    buf.get(recordBytes);
    return new RecordImage(true, ImmutableBytes.copyOf(recordBytes));
  }

  /**
   * Writes or updates a record image at the specified slot and slot version.
   *
   * @param pageData binary page data
   * @param slotNumber target slot index
   * @param slotVersion target slot version (reuse counter from RecordId.generation)
   * @param image record image to write (or absent image to delete the slot)
   * @return updated page bytes
   * @throws DbmsException if parameters are invalid or page space is insufficient
   */
  public ImmutableBytes writeSlot(
      ImmutableBytes pageData, int slotNumber, long slotVersion, RecordImage image) {
    if (image == null) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "RecordImage must not be null");
    }
    if (slotNumber < 0) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "slotNumber must not be negative");
    }
    if (slotVersion < 0) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "slotVersion must not be negative");
    }
    if (!image.exists()) {
      return deleteSlotIfPresent(pageData, slotNumber, slotVersion);
    }

    byte[] recordBytes = image.encoded().copy();
    int recordSize = recordBytes.length;
    if (recordSize == 0) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Live RecordImage payload must not be empty");
    }
    if (recordSize > MAX_RECORD_SIZE) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Record size ("
              + recordSize
              + " bytes) exceeds maximum slotted page record size ("
              + MAX_RECORD_SIZE
              + " bytes)");
    }

    ByteBuffer buf = validateAndWrapPage(pageData);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    int targetSlotCount = Math.max(slotCount, slotNumber + 1);

    int usedOtherRecordSize = 0;
    for (int i = 0; i < slotCount; i++) {
      if (i == slotNumber) {
        continue;
      }
      int pos = slotEntryOffset(i);
      if (buf.get(pos) == SLOT_LIVE) {
        usedOtherRecordSize += Short.toUnsignedInt(buf.getShort(pos + 3));
      }
    }

    int newDirEndOffset = directoryEndOffset(targetSlotCount);
    if (newDirEndOffset + usedOtherRecordSize + recordSize > PAGE_SIZE) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Insufficient space in page to write " + recordSize + " bytes at slot " + slotNumber);
    }

    int freeSpaceEndOffset = effectiveFreeSpaceEndOffset(buf);

    if (slotNumber < slotCount && targetSlotCount == slotCount) {
      int pos = slotEntryOffset(slotNumber);
      byte oldStatus = buf.get(pos);
      int oldOffset = Short.toUnsignedInt(buf.getShort(pos + 1));
      int oldSize = Short.toUnsignedInt(buf.getShort(pos + 3));
      if (oldStatus == SLOT_LIVE && oldSize == recordSize) {
        buf.position(oldOffset);
        buf.put(recordBytes);
        writeHeader(buf, slotCount, freeSpaceEndOffset);
        writeSlotEntry(buf, slotNumber, SLOT_LIVE, oldOffset, recordSize, slotVersion);
        return ImmutableBytes.copyOf(buf.array());
      }
      if (oldStatus == SLOT_LIVE) {
        writeSlotEntry(buf, slotNumber, SLOT_EMPTY, 0, 0, slotVersion);
      }
    }

    if (targetSlotCount > slotCount) {
      if (newDirEndOffset > freeSpaceEndOffset) {
        buf = ByteBuffer.wrap(compactPageInternal(buf));
        freeSpaceEndOffset = effectiveFreeSpaceEndOffset(buf);
      }
      for (int i = slotCount; i < targetSlotCount; i++) {
        writeSlotEntry(buf, i, SLOT_EMPTY, 0, 0, 0L);
      }
      slotCount = targetSlotCount;
      writeHeader(buf, slotCount, freeSpaceEndOffset);
    }

    if (freeSpaceEndOffset - newDirEndOffset < recordSize) {
      writeSlotEntry(buf, slotNumber, SLOT_EMPTY, 0, 0, slotVersion);
      buf = ByteBuffer.wrap(compactPageInternal(buf));
      freeSpaceEndOffset = effectiveFreeSpaceEndOffset(buf);
    }

    int recordOffset = freeSpaceEndOffset - recordSize;
    if (recordOffset < newDirEndOffset) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Page overflow while writing slot " + slotNumber);
    }

    buf.position(recordOffset);
    buf.put(recordBytes);
    writeHeader(buf, slotCount, recordOffset);
    writeSlotEntry(buf, slotNumber, SLOT_LIVE, recordOffset, recordSize, slotVersion);
    return ImmutableBytes.copyOf(buf.array());
  }

  /**
   * Deletes a live slot if its slot version matches expectedSlotVersion, preserving its slot
   * version so future reuse increments from the current value, and compacts the page.
   *
   * @param pageData binary page data
   * @param slotNumber slot index to delete
   * @param expectedSlotVersion expected slot version of the record to delete
   * @return updated and compacted page bytes
   * @throws DbmsException if the slot is not live or its slot version does not match
   */
  public ImmutableBytes deleteSlot(
      ImmutableBytes pageData, int slotNumber, long expectedSlotVersion) {
    ByteBuffer buf = validateAndWrapPage(pageData);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    requireValidSlotIndex(slotNumber, slotCount);

    int pos = slotEntryOffset(slotNumber);
    byte status = buf.get(pos);
    long currentVersion = buf.getLong(pos + 5);
    if (status != SLOT_LIVE || currentVersion != expectedSlotVersion) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Cannot delete non-live or mismatched-version slot "
              + slotNumber
              + " (status="
              + status
              + ", actualVersion="
              + currentVersion
              + ", expectedVersion="
              + expectedSlotVersion
              + ")");
    }

    writeSlotEntry(buf, slotNumber, SLOT_EMPTY, 0, 0, currentVersion);
    byte[] compacted = compactPageInternal(buf);
    return ImmutableBytes.copyOf(compacted);
  }

  /**
   * Compacts the page by packing all live records at the end of the page.
   *
   * @param pageData binary page data
   * @return compacted page bytes
   * @throws DbmsException if page data is null or corrupted
   */
  public ImmutableBytes compactPage(ImmutableBytes pageData) {
    ByteBuffer buf = validateAndWrapPage(pageData);
    return ImmutableBytes.copyOf(compactPageInternal(buf));
  }

  private ImmutableBytes deleteSlotIfPresent(
      ImmutableBytes pageData, int slotNumber, long slotVersion) {
    ByteBuffer buf = validateAndWrapPage(pageData);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    if (slotNumber < 0 || slotNumber >= slotCount) {
      return pageData;
    }
    int pos = slotEntryOffset(slotNumber);
    byte status = buf.get(pos);
    long currentVersion = buf.getLong(pos + 5);
    if (status == SLOT_EMPTY) {
      if (slotVersion > currentVersion) {
        writeSlotEntry(buf, slotNumber, SLOT_EMPTY, 0, 0, slotVersion);
        return ImmutableBytes.copyOf(buf.array());
      }
      return pageData;
    }
    long targetVersion = Math.max(currentVersion, slotVersion);
    writeSlotEntry(buf, slotNumber, SLOT_EMPTY, 0, 0, targetVersion);
    return ImmutableBytes.copyOf(compactPageInternal(buf));
  }

  private static byte[] compactPageInternal(ByteBuffer srcBuf) {
    int slotCount = Short.toUnsignedInt(srcBuf.getShort(4));
    byte[] dst = new byte[PAGE_SIZE];
    ByteBuffer dstBuf = ByteBuffer.wrap(dst);

    int writeCursorOffset = PAGE_SIZE;
    for (int i = 0; i < slotCount; i++) {
      int pos = slotEntryOffset(i);
      byte status = srcBuf.get(pos);
      int oldOffset = Short.toUnsignedInt(srcBuf.getShort(pos + 1));
      int recordSize = Short.toUnsignedInt(srcBuf.getShort(pos + 3));
      long slotVersion = srcBuf.getLong(pos + 5);

      if (status == SLOT_LIVE) {
        int newOffset = writeCursorOffset - recordSize;
        if (newOffset < directoryEndOffset(slotCount)) {
          throw new DbmsException(
              ErrorCode.STORAGE_FAILURE, "Corrupted slotted page: overlapping records on compact");
        }
        System.arraycopy(srcBuf.array(), oldOffset, dst, newOffset, recordSize);
        writeSlotEntry(dstBuf, i, SLOT_LIVE, newOffset, recordSize, slotVersion);
        writeCursorOffset = newOffset;
      } else {
        writeSlotEntry(dstBuf, i, SLOT_EMPTY, 0, 0, slotVersion);
      }
    }

    writeHeader(dstBuf, slotCount, writeCursorOffset);
    return dst;
  }

  private static ByteBuffer validateAndWrapPage(ImmutableBytes pageData) {
    if (pageData == null) {
      throw new DbmsException(ErrorCode.STORAGE_FAILURE, "Page data must not be null");
    }
    byte[] raw = pageData.copy();
    if (raw.length != PAGE_SIZE) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Invalid page size: " + raw.length + ", expected: " + PAGE_SIZE);
    }

    ByteBuffer buf = ByteBuffer.wrap(raw);
    short magic = buf.getShort(0);
    byte version = buf.get(2);
    byte reserved = buf.get(3);
    int slotCount = Short.toUnsignedInt(buf.getShort(4));
    int rawFreeSpaceEndOffset = Short.toUnsignedInt(buf.getShort(6));

    // Fresh zero-filled page from DiskManager.appendPage()
    if (magic == 0
        && version == 0
        && reserved == 0
        && slotCount == 0
        && rawFreeSpaceEndOffset == 0) {
      return buf;
    }

    if (magic != PAGE_MAGIC || version != FORMAT_VERSION || reserved != 0) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE, "Corrupted slotted page header magic or format version");
    }

    int dirEndOffset = directoryEndOffset(slotCount);
    if (dirEndOffset > PAGE_SIZE
        || rawFreeSpaceEndOffset < dirEndOffset
        || rawFreeSpaceEndOffset > PAGE_SIZE) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Corrupted slotted page pointers (slotCount="
              + slotCount
              + ", freeSpaceEndOffset="
              + rawFreeSpaceEndOffset
              + ")");
    }

    for (int i = 0; i < slotCount; i++) {
      int pos = slotEntryOffset(i);
      byte status = buf.get(pos);
      int recordOffset = Short.toUnsignedInt(buf.getShort(pos + 1));
      int recordSize = Short.toUnsignedInt(buf.getShort(pos + 3));
      long slotVersion = buf.getLong(pos + 5);
      if (slotVersion < 0) {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE, "Negative slot version in slot " + i + ": " + slotVersion);
      }
      if (status == SLOT_LIVE) {
        if (recordSize <= 0
            || recordOffset < rawFreeSpaceEndOffset
            || recordOffset + recordSize > PAGE_SIZE) {
          throw new DbmsException(
              ErrorCode.STORAGE_FAILURE,
              "Corrupted live slot "
                  + i
                  + " (recordOffset="
                  + recordOffset
                  + ", recordSize="
                  + recordSize
                  + ")");
        }
      } else if (status == SLOT_EMPTY) {
        if (recordOffset != 0 || recordSize != 0) {
          throw new DbmsException(
              ErrorCode.STORAGE_FAILURE, "Corrupted empty slot " + i + " metadata");
        }
      } else {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE, "Unknown slot status " + status + " at slot " + i);
      }
    }

    return buf;
  }

  private static int effectiveFreeSpaceEndOffset(ByteBuffer buf) {
    short magic = buf.getShort(0);
    int rawFreeSpaceEndOffset = Short.toUnsignedInt(buf.getShort(6));
    return (magic == 0 && rawFreeSpaceEndOffset == 0) ? PAGE_SIZE : rawFreeSpaceEndOffset;
  }

  private static void writeHeader(ByteBuffer buf, int slotCount, int freeSpaceEndOffset) {
    buf.putShort(0, PAGE_MAGIC);
    buf.put(2, FORMAT_VERSION);
    buf.put(3, (byte) 0);
    buf.putShort(4, (short) slotCount);
    buf.putShort(6, (short) freeSpaceEndOffset);
  }

  private static void writeSlotEntry(
      ByteBuffer buf,
      int slotNumber,
      byte status,
      int recordOffset,
      int recordSize,
      long slotVersion) {
    int pos = slotEntryOffset(slotNumber);
    buf.put(pos, status);
    buf.putShort(pos + 1, (short) recordOffset);
    buf.putShort(pos + 3, (short) recordSize);
    buf.putLong(pos + 5, slotVersion);
  }

  private static int slotEntryOffset(int slotNumber) {
    return PAGE_HEADER_SIZE + slotNumber * SLOT_SIZE;
  }

  private static int directoryEndOffset(int slotCount) {
    return PAGE_HEADER_SIZE + slotCount * SLOT_SIZE;
  }

  private static void requireValidSlotIndex(int slotNumber, int slotCount) {
    if (slotNumber < 0 || slotNumber >= slotCount) {
      throw new DbmsException(
          ErrorCode.STORAGE_FAILURE,
          "Slot index out of bounds: " + slotNumber + " (slotCount=" + slotCount + ")");
    }
  }
}
