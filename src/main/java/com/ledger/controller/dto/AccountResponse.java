package com.ledger.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ledger.model.Account;
import com.ledger.model.AccountStatus;
import java.time.Instant;

public record AccountResponse(
        @JsonProperty("_id") String id,
        String user,
        AccountStatus status,
        String currency,
        Instant createdAt,
        Instant updatedAt,
        @JsonProperty("__v") Integer version) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId().toHexString(),
                account.getUser().toHexString(),
                account.getStatus(),
                account.getCurrency(),
                account.getCreatedAt(),
                account.getUpdatedAt(),
                account.getVersion());
    }
}
