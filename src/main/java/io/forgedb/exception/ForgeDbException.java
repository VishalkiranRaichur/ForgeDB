package io.forgedb.exception;

public class ForgeDbException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ForgeDbException(String message) {
        super(message);
    }

    public ForgeDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
