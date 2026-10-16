package edu.cwru.openbench.common;

/**
 * The caller sees the object but may not act on it (HTTP 403, code {@code FORBIDDEN}). A missing
 * role is not reported with this class: {@code @PreAuthorize} raises Spring's
 * {@code AccessDeniedException}, which maps to the same response.
 */
public class ForbiddenException extends RuntimeException {

    /** @param message one sentence the client shows as is */
    public ForbiddenException(String message) {
        super(message);
    }
}
