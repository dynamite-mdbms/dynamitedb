package dynamitedb.contracts;

import java.util.Objects;

/** Typed failure at a component boundary. */
public final class DbmsException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  public final ErrorCode code;

  public DbmsException(ErrorCode code, String message) {
    super(Objects.requireNonNull(message, "message"));
    this.code = Objects.requireNonNull(code, "code");
  }

  public ErrorCode getCode() {
    return code;
  }
}
