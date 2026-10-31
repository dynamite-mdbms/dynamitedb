package dynamitedb.contracts.error;

// draf dari QP biar bisa compile, sesuai shared types final
public class DbmsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode code;

  public DbmsException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
