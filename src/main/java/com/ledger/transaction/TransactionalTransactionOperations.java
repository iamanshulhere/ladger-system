package com.ledger.transaction;

import com.ledger.controller.dto.InitialFundsRequest;
import com.ledger.controller.dto.TransferRequest;
import com.ledger.model.Account;
import com.ledger.model.AccountStatus;
import com.ledger.model.Ledger;
import com.ledger.model.LedgerEntryType;
import com.ledger.model.Transaction;
import com.ledger.model.TransactionStatus;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.LedgerRepository;
import com.ledger.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalTransactionOperations {

    private static final String ACTIVE_ACCOUNTS_MESSAGE =
            "Both fromAccount and toAccount must be ACTIVE to process transaction";
    private static final String ACTIVE_INITIAL_FUNDS_MESSAGE =
            "System and destination accounts must be ACTIVE to process initial funds";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerRepository ledgerRepository;

    public TransactionalTransactionOperations(AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional
    public TransactionOutcome transfer(ObjectId callerId, TransferRequest request, ObjectId fromId,
            ObjectId toId, BigDecimal amount) {
        Account from = accountRepository.findById(fromId)
                .orElseThrow(() -> new TransactionException(HttpStatus.BAD_REQUEST,
                        "Invalid fromAccount or toAccount"));
        Account to = accountRepository.findById(toId)
                .orElseThrow(() -> new TransactionException(HttpStatus.BAD_REQUEST,
                        "Invalid fromAccount or toAccount"));

        if (!callerId.equals(from.getUser())) {
            throw new TransactionException(HttpStatus.FORBIDDEN, "Not authorized to debit source account");
        }
        if (fromId.equals(toId)) {
            throw new TransactionException(HttpStatus.BAD_REQUEST, "Source and destination accounts must differ");
        }

        Optional<Transaction> prior = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
        if (prior.isPresent()) {
            ensureSameRequest(prior.get(), fromId, toId, amount);
            return existingOutcome(prior.get(), false);
        }

        if (from.getStatus() != AccountStatus.ACTIVE || to.getStatus() != AccountStatus.ACTIVE) {
            throw new TransactionException(HttpStatus.BAD_REQUEST, ACTIVE_ACCOUNTS_MESSAGE);
        }

        acquireAccountWrites(fromId, callerId, toId);
        BigDecimal balance = calculateBalance(fromId);
        if (balance.compareTo(amount) < 0) {
            throw new TransactionException(HttpStatus.BAD_REQUEST,
                    "Insufficient balance. Current balance is " + balance.toPlainString()
                            + ". Requested amount is " + amount.toPlainString());
        }

        Transaction transaction = savePendingTransaction(fromId, toId, amount, request.idempotencyKey());
        ledgerRepository.insert(new Ledger(fromId, amount, transaction.getId(), LedgerEntryType.DEBIT));
        ledgerRepository.insert(new Ledger(toId, amount, transaction.getId(), LedgerEntryType.CREDIT));
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction = transactionRepository.save(transaction);

        return new TransactionOutcome(HttpStatus.CREATED,
                "Transaction completed successfully", transaction);
    }

    @Transactional
    public TransactionOutcome initialFunds(ObjectId systemUserId, InitialFundsRequest request,
            ObjectId toId, BigDecimal amount) {
        Account to = accountRepository.findById(toId)
                .orElseThrow(() -> new TransactionException(HttpStatus.BAD_REQUEST, "Invalid toAccount"));
        List<Account> systemAccounts = accountRepository.findByUser(systemUserId);
        Account from = systemAccounts.stream().findFirst()
                .orElseThrow(() -> new TransactionException(HttpStatus.BAD_REQUEST,
                        "System user account not found"));

        if (from.getId().equals(toId)) {
            throw new TransactionException(HttpStatus.BAD_REQUEST, "Source and destination accounts must differ");
        }

        Optional<Transaction> prior = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
        if (prior.isPresent()) {
            ensureSameRequest(prior.get(), from.getId(), toId, amount);
            return existingOutcome(prior.get(), true);
        }

        if (from.getStatus() != AccountStatus.ACTIVE || to.getStatus() != AccountStatus.ACTIVE) {
            throw new TransactionException(HttpStatus.BAD_REQUEST, ACTIVE_INITIAL_FUNDS_MESSAGE);
        }

        acquireAccountWritesOwnedBy(from.getId(), systemUserId, toId, ACTIVE_INITIAL_FUNDS_MESSAGE);

        Transaction transaction = savePendingTransaction(from.getId(), toId, amount, request.idempotencyKey());
        ledgerRepository.insert(new Ledger(from.getId(), amount, transaction.getId(), LedgerEntryType.DEBIT));
        ledgerRepository.insert(new Ledger(toId, amount, transaction.getId(), LedgerEntryType.CREDIT));
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction = transactionRepository.save(transaction);

        return new TransactionOutcome(HttpStatus.CREATED,
                "Initial funds transaction completed successfully", transaction);
    }

    private void acquireAccountWrites(ObjectId fromId, ObjectId ownerId, ObjectId toId) {
        acquireAccountWritesOwnedBy(fromId, ownerId, toId, ACTIVE_ACCOUNTS_MESSAGE);
    }

    private void acquireAccountWritesOwnedBy(ObjectId fromId, ObjectId ownerId, ObjectId toId,
            String errorMessage) {
        if (!accountRepository.touchActiveAccountOwnedBy(fromId, ownerId)
                || !accountRepository.touchActiveAccount(toId)) {
            throw new TransactionException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }

    private BigDecimal calculateBalance(ObjectId accountId) {
        return ledgerRepository.findByAccount(accountId).stream()
                .map(entry -> entry.getAmount() == null || entry.getType() == null
                        ? BigDecimal.ZERO
                        : entry.getType() == LedgerEntryType.CREDIT
                                ? entry.getAmount()
                                : entry.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Transaction savePendingTransaction(ObjectId fromId, ObjectId toId,
            BigDecimal amount, String idempotencyKey) {
        Transaction transaction = new Transaction();
        transaction.setFromAccount(fromId);
        transaction.setToAccount(toId);
        transaction.setAmount(amount);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setStatus(TransactionStatus.PENDING);
        return transactionRepository.save(transaction);
    }

    private void ensureSameRequest(Transaction existing, ObjectId fromId, ObjectId toId, BigDecimal amount) {
        if (!fromId.equals(existing.getFromAccount())
                || !toId.equals(existing.getToAccount())
                || existing.getAmount().compareTo(amount) != 0) {
            throw new TransactionException(HttpStatus.CONFLICT,
                    "Idempotency key has already been used for a different transaction");
        }
    }

    public static TransactionOutcome transferExistingOutcome(Transaction transaction) {
        return existingOutcome(transaction, false);
    }

    public static TransactionOutcome initialFundsExistingOutcome(Transaction transaction) {
        return existingOutcome(transaction, true);
    }

    private static TransactionOutcome existingOutcome(Transaction transaction, boolean initialFunds) {
        if (transaction.getStatus() == TransactionStatus.COMPLETED) {
            return new TransactionOutcome(HttpStatus.OK,
                    initialFunds ? "Initial funds transaction already processed" : "Transaction already processed",
                    transaction);
        }
        if (transaction.getStatus() == TransactionStatus.PENDING) {
            return new TransactionOutcome(HttpStatus.OK, "Transaction is still processing", null);
        }
        if (transaction.getStatus() == TransactionStatus.FAILED) {
            return new TransactionOutcome(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Transaction processing failed, please retry", null);
        }
        return new TransactionOutcome(HttpStatus.INTERNAL_SERVER_ERROR,
                "Transaction was reversed, please retry", null);
    }
}
