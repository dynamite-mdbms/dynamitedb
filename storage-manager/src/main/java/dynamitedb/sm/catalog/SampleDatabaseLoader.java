package dynamitedb.sm.catalog;

import dynamitedb.contracts.api.ChangeLogger;
import dynamitedb.contracts.api.TransactionManager;
import dynamitedb.contracts.api.TupleReader;
import dynamitedb.contracts.api.WriteStore;
import dynamitedb.contracts.error.DbmsException;
import dynamitedb.contracts.error.ErrorCode;
import dynamitedb.contracts.recovery.LogSequenceNumber;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.FloatValue;
import dynamitedb.contracts.storage.IntValue;
import dynamitedb.contracts.storage.NullValue;
import dynamitedb.contracts.storage.Row;
import dynamitedb.contracts.storage.Schema;
import dynamitedb.contracts.storage.TextValue;
import dynamitedb.contracts.storage.Value;
import dynamitedb.contracts.transaction.TransactionId;
import dynamitedb.contracts.transaction.TransactionStatus;
import dynamitedb.sm.serialization.RecordSerializer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Seeds related sample tables through the normal transaction/WAL/SM1 write path (SM-14). */
public final class SampleDatabaseLoader {
  private final WriteStore storage;
  private final ChangeLogger logger;
  private final TransactionManager transactions;
  private final TupleReader reader;
  private final RecordSerializer serializer = new RecordSerializer();

  /** Retained for the original declaration; first-time loading requires the injected ports. */
  public SampleDatabaseLoader() {
    storage = null;
    logger = null;
    transactions = null;
    reader = null;
  }

  public SampleDatabaseLoader(
      WriteStore storage, ChangeLogger logger, TransactionManager transactions) {
    this(storage, logger, transactions, storage instanceof TupleReader tuples ? tuples : null);
  }

  /** A separate reader may be supplied when WriteStore and TupleReader have different providers. */
  public SampleDatabaseLoader(
      WriteStore storage,
      ChangeLogger logger,
      TransactionManager transactions,
      TupleReader reader) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.logger = Objects.requireNonNull(logger, "logger");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.reader = reader;
  }

  /**
   * Call during exclusive startup, after successful crash recovery and before accepting queries.
   * Interrupted writes are reconciled against the recovered heap, never against cached statistics
   * or a guessed transaction outcome. SM1 updates statistics as logged writes are applied.
   */
  public void loadIfAbsent(CatalogManager catalog) {
    Objects.requireNonNull(catalog, "catalog");
    synchronized (catalog) {
      if (catalog.hasDatabase()) {
        return;
      }
      if (storage == null) {
        throw new DbmsException(
            ErrorCode.INVALID_STATE,
            "Sample loading requires WriteStore, ChangeLogger and TransactionManager");
      }
      var definitions = sampleSchemas();
      var rows = sampleRows();
      if (catalog.requiresSampleRecovery()) {
        catalog.validateSampleDefinitions(definitions);
        if (recoverInitialization(catalog, rows)) {
          return;
        }
      }
      catalog.beginSampleInitialization(definitions);
      TransactionId tx = transactions.begin();
      boolean committed = false;
      try {
        catalog.startSampleWrites();
        for (String table : List.of("student", "course", "enrollment")) {
          for (Row row : rows.get(table)) {
            insert(catalog, tx, table, row);
          }
        }
        transactions.commit(tx);
        committed = true;
        catalog.completeSampleInitialization();
      } catch (RuntimeException e) {
        if (!committed) {
          try {
            transactions.abort(tx);
            if (transactions.status(tx) == TransactionStatus.ABORTED
                && catalog.requiresSampleRecovery()) {
              catalog.resetSampleInitialization();
            }
          } catch (RuntimeException abortFailure) {
            e.addSuppressed(abortFailure);
          }
        }
        throw e;
      }
    }
  }

  /** Returns true only when the full committed seed is present after recovery. */
  private boolean recoverInitialization(CatalogManager catalog, Map<String, List<Row>> expected) {
    if (reader == null) {
      throw new DbmsException(
          ErrorCode.INVALID_STATE,
          "Interrupted sample initialization requires a TupleReader over the recovered heap");
    }
    TransactionId tx = transactions.begin();
    boolean committed = false;
    try {
      var recovered = new HashMap<String, List<Row>>();
      boolean empty = true;
      boolean complete = true;
      for (String table : List.of("student", "course", "enrollment")) {
        var remaining = new HashMap<Row, Integer>();
        expected.get(table).forEach(row -> remaining.merge(row, 1, Integer::sum));
        var actual = new ArrayList<Row>();
        try (var cursor = reader.openTableScan(table, tx)) {
          for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
            var stored = next.orElseThrow();
            int count = remaining.getOrDefault(stored.row(), 0);
            if (stored.rid() == null
                || stored.rid().tableId() != catalog.getTableId(table)
                || count == 0) {
              throw new DbmsException(
                  ErrorCode.STORAGE_FAILURE,
                  "Recovered sample contains unexpected or duplicate rows; refusing to reseed");
            }
            remaining.put(stored.row(), count - 1);
            actual.add(stored.row());
          }
        }
        recovered.put(table, List.copyOf(actual));
        empty &= actual.isEmpty();
        complete &= actual.size() == expected.get(table).size();
      }
      if (!empty && !complete) {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE,
            "Recovered sample is partial; recovery must resolve the seed transaction before loading");
      }
      transactions.commit(tx);
      committed = true;
      if (complete) {
        recovered.forEach(catalog::refreshStatistics);
        catalog.completeSampleInitialization();
      } else {
        catalog.resetSampleInitialization();
      }
      return complete;
    } catch (RuntimeException e) {
      if (!committed) {
        try {
          transactions.abort(tx);
        } catch (RuntimeException abortFailure) {
          e.addSuppressed(abortFailure);
        }
      }
      throw e;
    }
  }

  private void insert(CatalogManager catalog, TransactionId tx, String table, Row row) {
    var expected = serializer.encode(row, catalog.getSchema(table));
    var prepared = storage.prepareInsert(tx, table, row);
    LogSequenceNumber lsn;
    try {
      if (prepared == null
          || !tx.equals(prepared.tx())
          || !table.equals(prepared.table())
          || prepared.rid() == null
          || prepared.rid().tableId() != catalog.getTableId(table)
          || prepared.before() == null
          || prepared.before().exists()
          || !expected.equals(prepared.after())
          || prepared.reservation() == null) {
        throw new DbmsException(
            ErrorCode.STORAGE_FAILURE,
            "SM1 returned an invalid sample insert reservation for " + table);
      }
      lsn = logger.recordChange(tx, prepared);
    } catch (RuntimeException e) {
      if (prepared != null) {
        try {
          storage.cancelPrepared(prepared);
        } catch (RuntimeException cancelFailure) {
          e.addSuppressed(cancelFailure);
        }
      }
      throw e;
    }
    storage.applyWrite(prepared, lsn);
  }

  private static Map<String, List<Row>> sampleRows() {
    var students = new ArrayList<Row>();
    var courses = new ArrayList<Row>();
    var enrollments = new ArrayList<Row>();
    for (int i = 1; i <= 50; i++) {
      students.add(
          new Row(
              List.of(
                  new IntValue(i),
                  new TextValue("Student " + i),
                  i % 10 == 0 ? NullValue.INSTANCE : new FloatValue(2.0 + (i % 9) * 0.25),
                  new TextValue(String.valueOf((char) ('A' + i % 4))))));
      courses.add(
          new Row(
              List.of(
                  new IntValue(i),
                  new TextValue("Database Course " + i),
                  new IntValue(2 + i % 3),
                  new TextValue(String.format(Locale.ROOT, "CS%04d", i)))));
    }
    for (int i = 0; i < 100; i++) {
      Value score = i % 9 == 0 ? NullValue.INSTANCE : new FloatValue(60.0 + (i % 81) * 0.5);
      enrollments.add(
          new Row(
              List.of(
                  new IntValue(i % 50 + 1),
                  new IntValue((i % 50 + (i / 50) * 25) % 50 + 1),
                  score,
                  new TextValue(i % 9 == 0 ? "I" : "C"))));
    }
    return Map.of(
        "student",
        List.copyOf(students),
        "course",
        List.copyOf(courses),
        "enrollment",
        List.copyOf(enrollments));
  }

  private static Map<String, Schema> sampleSchemas() {
    return Map.of(
        "student",
            new Schema(
                List.of(
                    new Column("student", "student_id", DataType.INTEGER, 0, false),
                    new Column("student", "name", DataType.VARCHAR, 50, false),
                    new Column("student", "gpa", DataType.FLOAT, 0, true),
                    new Column("student", "grade", DataType.CHAR, 1, true))),
        "course",
            new Schema(
                List.of(
                    new Column("course", "course_id", DataType.INTEGER, 0, false),
                    new Column("course", "title", DataType.VARCHAR, 50, false),
                    new Column("course", "credits", DataType.INTEGER, 0, false),
                    new Column("course", "code", DataType.CHAR, 6, false))),
        "enrollment",
            new Schema(
                List.of(
                    new Column("enrollment", "student_id", DataType.INTEGER, 0, false),
                    new Column("enrollment", "course_id", DataType.INTEGER, 0, false),
                    new Column("enrollment", "score", DataType.FLOAT, 0, true),
                    new Column("enrollment", "status", DataType.CHAR, 1, true))));
  }
}
