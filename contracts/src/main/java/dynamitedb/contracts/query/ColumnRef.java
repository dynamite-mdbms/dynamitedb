package dynamitedb.contracts.query;

import java.util.Optional;

// draf dari QP biar bisa compile, nanti ganti punya QO
public record ColumnRef(Optional<String> relation, String column) {}
