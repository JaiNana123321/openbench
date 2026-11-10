package edu.cwru.openbench.requests;

/**
 * The answer to a Release: the line in its new state (Released, or PartlyReleased while some of it
 * stays allocated) and the Refund posted for it.
 */
public record ReleaseOutcome(RequestLine line, RefundQuote refund) {}
