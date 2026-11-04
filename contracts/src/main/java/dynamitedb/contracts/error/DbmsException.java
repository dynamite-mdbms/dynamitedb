package dynamitedb.contracts.error;

import java.util.Objects;

public final class DbmsException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public final ErrorCode code;

  public DbmsException(ErrorCode code, String message) {
    super(Objects.requireNonNull(message, "message"));
    this.code = Objects.requireNonNull(code, "code");
  }

  public DbmsException(ErrorCode code, String message, Throwable cause) {
    super(Objects.requireNonNull(message, "message"), cause);
    this.code = Objects.requireNonNull(code, "code");
  }

  public ErrorCode getCode() {
    return code;
  }
}
