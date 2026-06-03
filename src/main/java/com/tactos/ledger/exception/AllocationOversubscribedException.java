package com.tactos.ledger.exception;

/**
 * Thrown when an allocation request is rejected because the target round
 * is no longer in OPEN status (e.g., FULLY_SUBSCRIBED or CLOSED).
 */
public class AllocationOversubscribedException extends RuntimeException {
    public AllocationOversubscribedException(String message) {
        super(message);
    }
}
