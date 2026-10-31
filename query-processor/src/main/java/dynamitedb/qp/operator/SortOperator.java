package dynamitedb.qp.operator;

import dynamitedb.contracts.query.OperatorRow;
import dynamitedb.contracts.query.SortKey;
import java.util.List;
import java.util.Optional;

public class SortOperator implements Operator {
    private final Operator child;
    private final List<SortKey> keys;

    public SortOperator(Operator child, List<SortKey> keys) {
        this.child = child;
        this.keys = keys;
    }

    @Override
    public void open() {
        // TODO: open child
    }

    @Override
    public Optional<OperatorRow> next() {
        // TODO: kumpulkan semua baris child, urutkan berdasarkan keys, keluarkan satu per satu
        return Optional.empty();
    }

    @Override
    public void close() {
        // TODO: close child
    }
}