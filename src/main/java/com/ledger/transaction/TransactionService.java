package com.ledger.transaction;

import com.ledger.controller.dto.InitialFundsRequest;
import com.ledger.controller.dto.TransferRequest;
import com.ledger.model.Account;
import com.ledger.model.Transaction;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.TransactionRepository;
import com.mongodb.MongoException;
import java.math.BigDecimal;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    private static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private static final String TRANSIENT_TRANSACTION_ERROR = "TransientTransactionError";
    private static final String RETRY_MESSAGE =
            "Transaction could not be completed; retry using the same idempotencyKey";

    private final TransactionalTransactionOperations transactionOperations;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionalTransactionOperations transactionOperations,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository) {
        this.transactionOperations = transactionOperations;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public TransactionOutcome transfer(ObjectId callerId, TransferRequest request) {
        validateRequiredTransferFields(request);
        ObjectId fromId = parseObjectId(request.fromAccount(), "Invalid fromAccount or toAccount");
        ObjectId toId = parseObjectId(request.toAccount(), "Invalid fromAccount or toAccount");
        BigDecimal amount = normalizeAmount(request.amount());

        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return transactionOperations.transfer(callerId, request, fromId, toId, amount);
            } catch (DuplicateKeyException exception) {
                return replayTransfer(callerId, request, fromId, toId, amount);
            } catch (RuntimeException exception) {
                if (!isTransientTransactionError(exception)) {
                    throw exception;
                }
                if (attempt == MAX_TRANSACTION_ATTEMPTS) {
                    throw new TransactionException(HttpStatus.SERVICE_UNAVAILABLE, RETRY_MESSAGE);
                }
            }
        }
        throw new TransactionException(HttpStatus.SERVICE_UNAVAILABLE, RETRY_MESSAGE);
    }

    public TransactionOutcome initialFunds(ObjectId systemUserId, InitialFundsRequest request) {
        validateRequiredInitialFundsFields(request);
        ObjectId toId = parseObjectId(request.toAccount(), "Invalid toAccount");
        BigDecimal amount = normalizeAmount(request.amount());

        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                return transactionOperations.initialFunds(systemUserId, request, toId, amount);
            } catch (DuplicateKeyException exception) {
                return replayInitialFunds(systemUserId, request, toId, amount);
            } catch (RuntimeException exception) {
                if (!isTransientTransactionError(exception)) {
                    throw exception;
                }
                if (attempt == MAX_TRANSACTION_ATTEMPTS) {
                    throw new TransactionException(HttpStatus.SERVICE_UNAVAILABLE, RETRY_MESSAGE);
                }
            }
        }
        throw new TransactionException(HttpStatus.SERVICE_UNAVAILABLE, RETRY_MESSAGE);
    }

    private TransactionOutcome replayTransfer(ObjectId callerId, TransferRequest request,
            ObjectId fromId, ObjectId toId, BigDecimal amount) {
        Transaction transaction = transactionRepository.findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> new TransactionException(HttpStatus.SERVICE_UNAVAILABLE, RETRY_MESSAGE));
        Account originalSource = accountRepository.findById(transaction.getFromAccount()).orElse(null);
        if (!sameRequest(transaction, fromId, toId, amount)
                || originalSource == null
                || !callerId.equals(originalSource.getUser())) {
            throw idempotencyConflict();
        }
        return TransactionalTransactionOperations.transferExistingOutcome(transaction);
    }

    private TransactionOutcome replayInitialFunds(ObjectId systemUserId, InitialFundsRequest request,
            ObjectId toId, BigDecimal amount) {
        Transaction transaction = transactionRepository.findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> new TransactionException(HttpStatus.SERVICE_UNAVAILABLE, RETRY_MESSAGE));
        Optional<Account> systemSource = accountRepository.findByUser(systemUserId).stream().findFirst();
        if (systemSource.isEmpty()
                || !sameRequest(transaction, systemSource.get().getId(), toId, amount)) {
            throw idempotencyConflict();
        }
        return TransactionalTransactionOperations.initialFundsExistingOutcome(transaction);
    }

    private boolean sameRequest(Transaction transaction, ObjectId fromId, ObjectId toId, BigDecimal amount) {
        return fromId.equals(transaction.getFromAccount())
                && toId.equals(transaction.getToAccount())
                && transaction.getAmount() != null
                && transaction.getAmount().compareTo(amount) == 0;
    }

    private void validateRequiredTransferFields(TransferRequest request) {
        if (request == null || empty(request.fromAccount()) || empty(request.toAccount())
                || empty(request.idempotencyKey()) || request.amount() == null) {
            throw new TransactionException(HttpStatus.BAD_REQUEST,
                    "FromAccount, toAccount, amount and idempotencyKey are required");
        }
    }

    private void validateRequiredInitialFundsFields(InitialFundsRequest request) {
        if (request == null || empty(request.toAccount()) || empty(request.idempotencyKey())
                || request.amount() == null) {
            throw new TransactionException(HttpStatus.BAD_REQUEST,
                    "toAccount, amount and idempotencyKey are required");
        }
    }

    private boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    private ObjectId parseObjectId(String value, String message) {
        if (!ObjectId.isValid(value)) {
            throw new TransactionException(HttpStatus.BAD_REQUEST, message);
        }
        return new ObjectId(value);
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new TransactionException(HttpStatus.BAD_REQUEST,
                    "Transaction amount must be greater than zero");
        }
        double storedValue = amount.doubleValue();
        if (!Double.isFinite(storedValue) || storedValue <= 0) {
            throw new TransactionException(HttpStatus.BAD_REQUEST,
                    "Transaction amount cannot be represented as a positive BSON double");
        }
        // Match the repository's BSON-double conversion before doing decimal calculations.
        return BigDecimal.valueOf(storedValue);
    }

    private boolean isTransientTransactionError(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof MongoException mongoException
                    && mongoException.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR)) {
                return true;
            }
            if (current instanceof OptimisticLockingFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private TransactionException idempotencyConflict() {
        return new TransactionException(HttpStatus.CONFLICT,
                "Idempotency key has already been used for a different transaction");
    }
}
