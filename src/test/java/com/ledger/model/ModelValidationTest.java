package com.ledger.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ModelValidationTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void userNormalizesEmailAndAcceptsOriginalRequiredFields() {
        User user = new User("  Ada.Example@Example.com ", "Ada", "password-123", false);

        assertThat(user.getEmail()).isEqualTo("ada.example@example.com");
        assertThat(validator.validate(user)).isEmpty();
    }

    @Test
    void userRejectsInvalidEmailAndShortPassword() {
        User user = new User("not-an-email", "Ada", "short", false);

        assertThat(validator.validate(user))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("email", "password");
    }

    @Test
    void accountRequiresItsUserReference() {
        Account account = new Account();

        assertThat(validator.validate(account))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("user");
        assertThat(new Account(new ObjectId()).getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(new Account(new ObjectId()).getCurrency()).isEqualTo("INR");
    }

    @Test
    void transactionPreservesZeroMinimumAndRejectsNegativeAmounts() {
        Transaction zero = validTransaction(new BigDecimal("0.00"));
        Transaction negative = validTransaction(new BigDecimal("-0.01"));

        assertThat(validator.validate(zero)).isEmpty();
        assertThat(validator.validate(negative))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("amount");
    }

    private Transaction validTransaction(BigDecimal amount) {
        Transaction transaction = new Transaction();
        transaction.setFromAccount(new ObjectId());
        transaction.setToAccount(new ObjectId());
        transaction.setAmount(amount);
        transaction.setIdempotencyKey("phase-one-test-key");
        return transaction;
    }
}
