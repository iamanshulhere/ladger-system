package com.ledger.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ledger.model.Transaction;
import com.ledger.model.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionDocumentResponse(
        @JsonProperty("_id") String id,
        String fromAccount,
        String toAccount,
        TransactionStatus status,
        BigDecimal amount,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        @JsonProperty("__v") Integer version) {

    public static TransactionDocumentResponse from(Transaction transaction) {
        return new TransactionDocumentResponse(
                transaction.getId().toHexString(),
                transaction.getFromAccount().toHexString(),
                transaction.getToAccount().toHexString(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getIdempotencyKey(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt(),
                transaction.getVersion());
    }
}
