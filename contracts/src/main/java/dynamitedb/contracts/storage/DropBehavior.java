package dynamitedb.contracts.storage;

/** Opsi DROP TABLE: RESTRICT gagal bila masih direferensikan, CASCADE menghapus FK dependennya. */
public enum DropBehavior {
  RESTRICT,
  CASCADE
}
