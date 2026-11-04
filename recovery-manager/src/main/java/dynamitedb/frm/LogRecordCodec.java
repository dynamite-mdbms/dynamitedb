package dynamitedb.frm;

import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.ChangeRecord;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.recovery.RecoveryMutation;
import dynamitedb.contracts.storage.ImmutableBytes;
import dynamitedb.contracts.storage.RecordId;
import dynamitedb.contracts.storage.RecordImage;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.frm.mock.MockRecoveryContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;

/** Versioned binary encoding shared by normal logging, rollback, and recovery. */
public final class LogRecordCodec {
  static final int MAGIC = 0x44574C31;
  static final int MAX_PAYLOAD = 16 * 1024 * 1024;
  private final MockRecoveryContext mock;

  public LogRecordCodec() {
    this.mock = null;
  }

  public LogRecordCodec(MockRecoveryContext context) {
    this.mock = Objects.requireNonNull(context);
  }

  public ImmutableBytes encode(LogRecord record) {
    Objects.requireNonNull(record, "record");
    if (mock != null) {
      mock.invoked("LogRecordCodec.encode");
      return mock.frame();
    }
    try {
      var payload = new ByteArrayOutputStream();
      var out = new DataOutputStream(payload);
      out.writeByte(kind(record));
      out.writeLong(record.lsn.value());
      uuid(out, record.walLineage);
      optionalLsn(out, record.prevLsn);
      if (record instanceof ChangeLogRecord r) {
        tx(out, r.change.tx());
        out.writeUTF(r.change.table());
        rid(out, r.change.rid());
        image(out, r.change.before());
        image(out, r.change.after());
      } else if (record instanceof TransactionEventLogRecord r) {
        tx(out, r.tx.orElseThrow());
        out.writeByte(r.event.ordinal());
        out.writeBoolean(r.beginTimestamp.isPresent());
        if (r.beginTimestamp.isPresent()) out.writeLong(r.beginTimestamp.orElseThrow());
        out.writeBoolean(r.commitTime.isPresent());
        if (r.commitTime.isPresent()) instant(out, r.commitTime.orElseThrow());
      } else if (record instanceof CompensationLogRecord r) {
        tx(out, r.tx.orElseThrow());
        out.writeLong(r.compensates.value());
        optionalLsn(out, r.undoNext);
        rid(out, r.mutation.rid());
        image(out, r.mutation.targetImage());
      } else if (record instanceof CheckpointLogRecord r) {
        uuid(out, r.checkpointId);
        out.writeByte(r.event.ordinal());
        out.writeLong(r.throughLsn.value());
      }
      byte[] data = payload.toByteArray();
      if (data.length > MAX_PAYLOAD) throw new IllegalArgumentException("WAL record too large");
      var frame = new ByteArrayOutputStream();
      var framed = new DataOutputStream(frame);
      framed.writeInt(MAGIC);
      framed.writeInt(data.length);
      framed.writeInt(headerChecksum(data.length));
      framed.write(data);
      framed.writeInt(crc(data));
      return ImmutableBytes.copyOf(frame.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public LogRecord decode(ImmutableBytes frame) {
    Objects.requireNonNull(frame, "frame");
    if (mock != null) {
      mock.invoked("LogRecordCodec.decode");
      if (!frame.equals(mock.frame())) throw corrupt("Unknown mock frame");
      return mock.record();
    }
    try {
      byte[] bytes = frame.copy();
      if (bytes.length < 16) throw corrupt("Short WAL frame");
      var input = new DataInputStream(new ByteArrayInputStream(bytes));
      if (input.readInt() != MAGIC) throw corrupt("Invalid WAL magic");
      int size = input.readInt();
      if (input.readInt() != headerChecksum(size))
        throw corrupt("WAL frame header checksum mismatch");
      if (size < 0 || size > MAX_PAYLOAD || size != bytes.length - 16)
        throw corrupt("Invalid WAL length");
      byte[] payload = new byte[size];
      input.readFully(payload);
      if (input.readInt() != crc(payload)) throw corrupt("WAL checksum mismatch");
      var in = new DataInputStream(new ByteArrayInputStream(payload));
      int kind = in.readUnsignedByte();
      var lsn = new LogSequenceNumber(in.readLong());
      if (lsn.value() <= 0) throw corrupt("Invalid LSN");
      UUID lineage = readUuid(in);
      Optional<LogSequenceNumber> prev = readOptionalLsn(in);
      LogRecord result;
      switch (kind) {
        case 1 -> {
          TransactionId tx = readTx(in);
          String table = in.readUTF();
          RecordId rid = readRid(in);
          result =
              new ChangeLogRecord(
                  lsn,
                  prev,
                  lineage,
                  new ChangeRecord(tx, table, rid, readImage(in), readImage(in)));
        }
        case 2 -> {
          TransactionId tx = readTx(in);
          int event = in.readUnsignedByte();
          if (event >= TxEvent.values().length) throw corrupt("Unknown transaction event");
          Optional<Long> ts = in.readBoolean() ? Optional.of(in.readLong()) : Optional.empty();
          Optional<Instant> time =
              in.readBoolean() ? Optional.of(readInstant(in)) : Optional.empty();
          result =
              new TransactionEventLogRecord(
                  lsn, tx, prev, lineage, TxEvent.values()[event], ts, time);
        }
        case 3 -> {
          TransactionId tx = readTx(in);
          var original = new LogSequenceNumber(in.readLong());
          Optional<LogSequenceNumber> undoNext = readOptionalLsn(in);
          result =
              new CompensationLogRecord(
                  lsn,
                  tx,
                  prev,
                  lineage,
                  original,
                  undoNext,
                  new RecoveryMutation(readRid(in), readImage(in)));
        }
        case 4 -> {
          UUID id = readUuid(in);
          int event = in.readUnsignedByte();
          if (event >= CheckpointEvent.values().length || prev.isPresent())
            throw corrupt("Invalid checkpoint");
          result =
              new CheckpointLogRecord(
                  lsn,
                  lineage,
                  id,
                  CheckpointEvent.values()[event],
                  new LogSequenceNumber(in.readLong()));
        }
        default -> throw corrupt("Unknown WAL record kind");
      }
      if (in.available() != 0) throw corrupt("Trailing WAL bytes");
      return result;
    } catch (DbmsException e) {
      throw e;
    } catch (IOException | IllegalArgumentException e) {
      throw corrupt("Invalid WAL frame: " + e.getMessage());
    }
  }

  public boolean verify(ImmutableBytes frame) {
    Objects.requireNonNull(frame, "frame");
    if (mock != null) {
      mock.invoked("LogRecordCodec.verify");
      return frame.equals(mock.frame());
    }
    try {
      decode(frame);
      return true;
    } catch (DbmsException e) {
      return false;
    }
  }

  private static int kind(LogRecord r) {
    if (r instanceof ChangeLogRecord) return 1;
    if (r instanceof TransactionEventLogRecord) return 2;
    if (r instanceof CompensationLogRecord) return 3;
    if (r instanceof CheckpointLogRecord) return 4;
    throw new IllegalArgumentException("Unknown WAL record class");
  }

  private static int crc(byte[] data) {
    var c = new CRC32();
    c.update(data);
    return (int) c.getValue();
  }

  static int headerChecksum(int length) {
    return crc(java.nio.ByteBuffer.allocate(8).putInt(MAGIC).putInt(length).array());
  }

  private static void uuid(DataOutputStream out, UUID x) throws IOException {
    out.writeLong(x.getMostSignificantBits());
    out.writeLong(x.getLeastSignificantBits());
  }

  private static UUID readUuid(DataInputStream in) throws IOException {
    return new UUID(in.readLong(), in.readLong());
  }

  private static void tx(DataOutputStream out, TransactionId x) throws IOException {
    out.writeLong(x.value());
  }

  private static TransactionId readTx(DataInputStream in) throws IOException {
    return new TransactionId(in.readLong());
  }

  private static void optionalLsn(DataOutputStream out, Optional<LogSequenceNumber> x)
      throws IOException {
    out.writeBoolean(x.isPresent());
    if (x.isPresent()) out.writeLong(x.orElseThrow().value());
  }

  private static Optional<LogSequenceNumber> readOptionalLsn(DataInputStream in)
      throws IOException {
    return in.readBoolean() ? Optional.of(new LogSequenceNumber(in.readLong())) : Optional.empty();
  }

  private static void rid(DataOutputStream out, RecordId x) throws IOException {
    out.writeLong(x.tableId());
    out.writeLong(x.pageNumber());
    out.writeInt(x.slotNumber());
    out.writeLong(x.generation());
  }

  private static RecordId readRid(DataInputStream in) throws IOException {
    return new RecordId(in.readLong(), in.readLong(), in.readInt(), in.readLong());
  }

  private static void image(DataOutputStream out, RecordImage x) throws IOException {
    byte[] bytes = x.encoded().copy();
    out.writeBoolean(x.exists());
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static RecordImage readImage(DataInputStream in) throws IOException {
    boolean exists = in.readBoolean();
    int size = in.readInt();
    if (size < 0 || size > MAX_PAYLOAD || size > in.available())
      throw corrupt("Invalid image length");
    byte[] bytes = new byte[size];
    in.readFully(bytes);
    return new RecordImage(exists, ImmutableBytes.copyOf(bytes));
  }

  private static void instant(DataOutputStream out, Instant x) throws IOException {
    out.writeLong(x.getEpochSecond());
    out.writeInt(x.getNano());
  }

  private static Instant readInstant(DataInputStream in) throws IOException {
    return Instant.ofEpochSecond(in.readLong(), in.readInt());
  }

  private static DbmsException corrupt(String message) {
    return new DbmsException(ErrorCode.CORRUPT_LOG, message);
  }
}
