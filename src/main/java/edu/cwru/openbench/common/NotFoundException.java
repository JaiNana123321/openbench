package edu.cwru.openbench.common;

/**
 * The object does not exist or lies outside the caller's scope (HTTP 404, code {@code NOT_FOUND}).
 * Out-of-scope objects are reported the same way so the caller learns nothing about them.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException() {
        this("The requested object does not exist.");
    }

    /** @param message one sentence the client shows as is */
    public NotFoundException(String message) {
        super(message);
    }
}
