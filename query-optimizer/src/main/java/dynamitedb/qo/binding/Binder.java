package dynamitedb.qo.binding;

import dynamitedb.contracts.api.CatalogAccess;
import dynamitedb.contracts.query.ColumnRef;
import dynamitedb.contracts.query.Statement;
import dynamitedb.contracts.storage.Column;
import dynamitedb.contracts.storage.DataType;
import dynamitedb.contracts.storage.Schema;

/** Memvalidasi statement terhadap katalog (QO-07). */
public class Binder {

  private final CatalogAccess catalog;

  public Binder(CatalogAccess catalog) {
    this.catalog = catalog;
  }

  public Statement bind(Statement statement) {
    // TODO: resolusi nama, ekspansi SELECT *, kolom NATURAL JOIN, dan pemeriksaan tipe
    return statement;
  }

  public Column resolve(ColumnRef column, Schema scope) {
    // TODO: cari kolom di scope, tolak kolom yang tidak ada atau ambigu
    return new Column(column.relation().orElse("mock"), column.column(), DataType.INTEGER, 0, true);
  }
}
