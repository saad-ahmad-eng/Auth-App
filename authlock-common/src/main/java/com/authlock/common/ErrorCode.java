package com.authlock.common;

/**
 * Standardized application-level error codes, per API-spec.md §3 Error
 * Model. Distinct from transport-level {@link java.rmi.RemoteException} —
 * these represent well-formed business-logic outcomes the client is
 * expected to handle and present to the user (PRD.md FR-012).
 */
public enum ErrorCode {
    AUTHENTICATION_FAILED,
    INVALID_SESSION,
    UNAUTHORIZED,
    FILE_NOT_FOUND,
    FILE_LOCKED,
    LOCK_NOT_OWNED,
    UPLOAD_FAILED,
    DOWNLOAD_FAILED,
    SERVER_ERROR
}
