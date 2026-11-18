package dynamitedb.contracts.query;

/** DROP TABLE behavior toward dependent foreign keys; RESTRICT is the SQL-standard default. */
public enum DropBehavior {
  RESTRICT,
  CASCADE
}
