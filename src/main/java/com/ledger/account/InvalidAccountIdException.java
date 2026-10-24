package com.ledger.account;

public class InvalidAccountIdException extends RuntimeException {
    public InvalidAccountIdException() {
        super("Invalid account ID");
    }
}
