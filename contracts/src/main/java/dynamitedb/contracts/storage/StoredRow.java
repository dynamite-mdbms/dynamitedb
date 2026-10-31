package dynamitedb.contracts.storage;

public class StoredRow {
  private final RecordId rid;
  private final Row row;

  public StoredRow(RecordId rid, Row row) {
    this.rid = rid;
    this.row = row;
  }

  public RecordId rid() {
    return rid;
  }

  public Row row() {
    return row;
  }
}
