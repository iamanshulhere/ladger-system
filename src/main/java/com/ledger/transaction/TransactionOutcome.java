package com.ledger.transaction;

import com.ledger.model.Transaction;
import org.springframework.http.HttpStatus;

public record TransactionOutcome(HttpStatus status, String message, Transaction transaction) {
}
