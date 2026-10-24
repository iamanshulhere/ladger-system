package com.ledger.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TransferRequest(
        @NotNull(message = "FromAccount, toAccount, amount and idempotencyKey are required")
        @Size(min = 1, message = "FromAccount, toAccount, amount and idempotencyKey are required")
        String fromAccount,
        @NotNull(message = "FromAccount, toAccount, amount and idempotencyKey are required")
        @Size(min = 1, message = "FromAccount, toAccount, amount and idempotencyKey are required")
        String toAccount,
        @NotNull(message = "FromAccount, toAccount, amount and idempotencyKey are required")
        @DecimalMin(value = "0.0", inclusive = false, message = "Transaction amount must be greater than zero")
        BigDecimal amount,
        @NotNull(message = "FromAccount, toAccount, amount and idempotencyKey are required")
        @Size(min = 1, message = "FromAccount, toAccount, amount and idempotencyKey are required")
        String idempotencyKey) {
}
