package com.ledger.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Document(collection = "transactions")
public class Transaction {

    @Id
    private ObjectId id;

    @NotNull
    @Indexed(name = "fromAccount_1")
    private ObjectId fromAccount;

    @NotNull
    @Indexed(name = "toAccount_1")
    private ObjectId toAccount;

    private TransactionStatus status = TransactionStatus.PENDING;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    @Field(targetType = FieldType.DOUBLE)
    private BigDecimal amount;

    @NotNull
    @Size(min = 1)
    @Indexed(name = "idempotencyKey_1", unique = true)
    private String idempotencyKey;

    @Field("__v")
    private Integer version = 0;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public Transaction() {
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public ObjectId getFromAccount() {
        return fromAccount;
    }

    public void setFromAccount(ObjectId fromAccount) {
        this.fromAccount = fromAccount;
    }

    public ObjectId getToAccount() {
        return toAccount;
    }

    public void setToAccount(ObjectId toAccount) {
        this.toAccount = toAccount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
