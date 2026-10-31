package dynamitedb.contracts.error;

public class DbmsException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public final ErrorCode code;

  public DbmsException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public DbmsException(ErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
