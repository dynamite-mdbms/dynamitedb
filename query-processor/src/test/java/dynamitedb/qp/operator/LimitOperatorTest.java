package dynamitedb.qp.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dynamitedb.contracts.query.OperatorRow;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LimitOperatorTest {
  @Test
  void doesNotPullChildAfterLimitIncludingZero() {
    CountingOperator child = new CountingOperator();
    LimitOperator limit = new LimitOperator(child, 1);
    limit.open();

    assertTrue(limit.next().isPresent());
    assertTrue(limit.next().isEmpty());
    assertEquals(1, child.nextCalls);
    limit.close();
    assertEquals(1, child.openCalls);
    assertEquals(1, child.closeCalls);

    child = new CountingOperator();
    limit = new LimitOperator(child, 0);
    limit.open();
    assertTrue(limit.next().isEmpty());
    assertEquals(0, child.nextCalls);
    limit.close();
    assertEquals(1, child.openCalls);
    assertEquals(1, child.closeCalls);
  }

  private static final class CountingOperator implements Operator {
    private int nextCalls;
    private int openCalls;
    private int closeCalls;

    @Override
    public void open() {
      openCalls++;
    }

    @Override
    public Optional<OperatorRow> next() {
      nextCalls++;
      return Optional.of(TestRows.row(TestRows.schema("t", "id"), 1));
    }

    @Override
    public void close() {
      closeCalls++;
    }
  }
}
