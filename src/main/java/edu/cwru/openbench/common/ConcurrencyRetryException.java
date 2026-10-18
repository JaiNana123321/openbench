package edu.cwru.openbench.common;

/**
 * An allocating transaction failed twice on a deadlock or lock timeout (HTTP 503 with
 * {@code Retry-After: 1}, code {@code CONCURRENCY_RETRY}). Nothing was changed.
 */
public class ConcurrencyRetryException extends RuntimeException {

    public ConcurrencyRetryException(String message, Throwable cause) {
        super(message, cause);
    }
}
