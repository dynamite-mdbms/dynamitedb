package dynamitedb.contracts.storage;

import java.util.List;

// draf dari QP sesuai shared types di CD final, nanti ganti kalau contracts resmi udah ada
public record Row(List<Value> values) {}
