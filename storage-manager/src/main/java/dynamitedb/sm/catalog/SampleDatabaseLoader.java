package dynamitedb.sm.catalog;

import java.util.Objects;

/** Memuat basis data contoh saat sistem pertama dijalankan (SM-14). */
public final class SampleDatabaseLoader {
  public void loadIfAbsent(CatalogManager catalog) {
    Objects.requireNonNull(catalog, "catalog");
    // TODO: minimal 3 tabel berelasi, 50 baris per tabel, keempat tipe data
    // TODO: jangan timpa database yang sudah ada atau rusak
  }
}
