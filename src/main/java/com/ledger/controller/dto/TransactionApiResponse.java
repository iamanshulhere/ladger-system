package com.ledger.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ledger.transaction.TransactionOutcome;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionApiResponse(String message, TransactionDocumentResponse transaction) {

    public static TransactionApiResponse from(TransactionOutcome outcome) {
        return new TransactionApiResponse(outcome.message(),
                outcome.transaction() == null ? null : TransactionDocumentResponse.from(outcome.transaction()));
    }
}
