package com.ledger.controller.dto;

import java.math.BigDecimal;
import org.bson.types.ObjectId;

public record AccountBalanceResponse(String accountId, BigDecimal balance) {

    public AccountBalanceResponse(ObjectId accountId, BigDecimal balance) {
        this(accountId.toHexString(), balance);
    }
}
