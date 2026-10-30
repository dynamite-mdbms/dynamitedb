package dynamitedb.contracts.storage;

import java.util.List;

// draf dari QP biar bisa compile, nanti ganti punya SM
// TODO: RecordId belum, nunggu keputusan dibawa lewat Row atau RowCursor
public record Row(List<Value> values) {}
