package com.authlock.common;

/**
 * Single checked exception carrying every application-level failure defined
 * in API-spec.md §3 Error Model, tagged with an {@link ErrorCode}.
 *
 * <p>Kept as one exception type (rather than a class per error code) so the
 * {@code VaultService} interface's throws clauses stay simple as methods are
 * added phase by phase — see API-spec.md's design note. Never carries
 * sensitive detail (passwords, tokens, keys, stack traces) in its message,
 * per Security.md ("never trust the client" / no internal detail leaked).
 */
public class VaultServiceException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public VaultServiceException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
