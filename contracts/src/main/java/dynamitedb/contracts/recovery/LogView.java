package dynamitedb.contracts.recovery;

import java.util.List;

public record LogView(List<String> lines) {
  public LogView {
    lines = List.copyOf(lines);
  }
}
