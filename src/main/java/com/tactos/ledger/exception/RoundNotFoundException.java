package com.tactos.ledger.exception;

/**
 * Thrown when an allocation request targets a funding round that does not exist.
 */
public class RoundNotFoundException extends RuntimeException {
    public RoundNotFoundException(String message) {
        super(message);
    }
}
