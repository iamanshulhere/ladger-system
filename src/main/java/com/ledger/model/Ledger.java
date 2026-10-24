package com.ledger.model;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Document(collection = "ledgers")
public class Ledger {

    @Id
    private ObjectId id;

    @NotNull
    @Indexed(name = "account_1")
    private ObjectId account;

    @NotNull
    @Field(targetType = FieldType.DOUBLE)
    private BigDecimal amount;

    @NotNull
    @Indexed(name = "transaction_1")
    private ObjectId transaction;

    @NotNull
    private LedgerEntryType type;

    public Ledger() {
    }

    public Ledger(ObjectId account, BigDecimal amount, ObjectId transaction, LedgerEntryType type) {
        this.account = account;
        this.amount = amount;
        this.transaction = transaction;
        this.type = type;
    }

    public ObjectId getId() {
        return id;
    }

    public ObjectId getAccount() {
        return account;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public ObjectId getTransaction() {
        return transaction;
    }

    public LedgerEntryType getType() {
        return type;
    }
}
