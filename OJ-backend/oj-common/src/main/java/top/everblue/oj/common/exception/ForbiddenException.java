package top.everblue.oj.common.exception;

/** Raised when an authenticated caller lacks permission for a domain resource. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
