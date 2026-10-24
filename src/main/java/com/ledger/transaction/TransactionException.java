package com.ledger.transaction;

import org.springframework.http.HttpStatus;

public class TransactionException extends RuntimeException {

    private final HttpStatus status;

    public TransactionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
