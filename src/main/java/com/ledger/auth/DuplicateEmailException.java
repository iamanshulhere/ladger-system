package com.ledger.auth;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("User already exists with email.");
    }
}
