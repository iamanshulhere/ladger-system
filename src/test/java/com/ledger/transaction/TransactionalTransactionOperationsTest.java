package com.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TransactionalTransactionOperationsTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LedgerRepository ledgerRepository;

    @InjectMocks
    private TransactionalTransactionOperations operations;

    @Test
    void transferCreatesCompletedTransactionAndMatchingDebitCreditEntries() {
        ObjectId ownerId = new ObjectId();
        Account from = account(new ObjectId(), ownerId, AccountStatus.ACTIVE);
        Account to = account(new ObjectId(), new ObjectId(), AccountStatus.ACTIVE);
        stubActiveTransfer(from, to, "transfer-key");
        when(ledgerRepository.findByAccount(from.getId())).thenReturn(List.of(
                ledger(from.getId(), "80.00", new ObjectId(), LedgerEntryType.CREDIT)));
        stubTransactionSave();

        TransactionOutcome outcome = operations.transfer(ownerId,
                new TransferRequest(from.getId().toHexString(), to.getId().toHexString(),
                        new BigDecimal("25.50"), "transfer-key"),
                from.getId(), to.getId(), new BigDecimal("25.50"));

        assertThat(outcome.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(outcome.transaction().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(outcome.transaction().getAmount()).isEqualByComparingTo("25.50");

        ArgumentCaptor<Ledger> entries = ArgumentCaptor.forClass(Ledger.class);
        verify(ledgerRepository, org.mockito.Mockito.times(2)).insert(entries.capture());
        assertThat(entries.getAllValues()).extracting(Ledger::getType)
                .containsExactly(LedgerEntryType.DEBIT, LedgerEntryType.CREDIT);
        assertThat(entries.getAllValues()).extracting(Ledger::getAccount)
                .containsExactly(from.getId(), to.getId());
        assertThat(entries.getAllValues()).allSatisfy(entry ->
                assertThat(entry.getTransaction()).isEqualTo(outcome.transaction().getId()));
    }

    @Test
    void insufficientFundsStopsBeforeCreatingTransactionOrLedgerEntries() {
        ObjectId ownerId = new ObjectId();
        Account from = account(new ObjectId(), ownerId, AccountStatus.ACTIVE);
        Account to = account(new ObjectId(), new ObjectId(), AccountStatus.ACTIVE);
        stubActiveTransfer(from, to, "low-balance-key");
        when(ledgerRepository.findByAccount(from.getId())).thenReturn(List.of(
                ledger(from.getId(), "10.00", new ObjectId(), LedgerEntryType.CREDIT)));

        assertThatThrownBy(() -> operations.transfer(ownerId,
                request(from, to, "20.00", "low-balance-key"),
                from.getId(), to.getId(), new BigDecimal("20.00")))
                .isInstanceOf(TransactionException.class)
                .hasMessageContaining("Insufficient balance");

        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(ledgerRepository, never()).insert(any(Ledger.class));
    }

    @Test
    void callerCannotDebitAnotherUsersAccount() {
        ObjectId callerId = new ObjectId();
        Account from = account(new ObjectId(), new ObjectId(), AccountStatus.ACTIVE);
        Account to = account(new ObjectId(), new ObjectId(), AccountStatus.ACTIVE);
        when(accountRepository.findById(from.getId())).thenReturn(Optional.of(from));
        when(accountRepository.findById(to.getId())).thenReturn(Optional.of(to));

        assertThatThrownBy(() -> operations.transfer(callerId,
                request(from, to, "1.00", "not-owner-key"),
                from.getId(), to.getId(), new BigDecimal("1.00")))
                .isInstanceOf(TransactionException.class)
                .extracting(exception -> ((TransactionException) exception).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(transactionRepository, never()).findByIdempotencyKey(any());
        verify(accountRepository, never()).touchActiveAccountOwnedBy(any(), any());
    }

    @Test
    void missingSourceAccountReturnsBadRequestBeforeCheckingTheDestination() {
        ObjectId ownerId = new ObjectId();
        ObjectId fromId = new ObjectId();
        ObjectId toId = new ObjectId();
        when(accountRepository.findById(fromId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operations.transfer(ownerId,
                new TransferRequest(fromId.toHexString(), toId.toHexString(),
                        new BigDecimal("1.00"), "missing-source"),
                fromId, toId, new BigDecimal("1.00")))
                .isInstanceOf(TransactionException.class)
                .hasMessage("Invalid fromAccount or toAccount");

        verify(accountRepository, never()).findById(toId);
        verify(transactionRepository, never()).findByIdempotencyKey(any());
        verify(ledgerRepository, never()).insert(any(Ledger.class));
    }

    @Test
    void missingDestinationAccountReturnsBadRequestWithoutWritingAnything() {
        ObjectId ownerId = new ObjectId();
        Account from = account(new ObjectId(), ownerId, AccountStatus.ACTIVE);
        ObjectId toId = new ObjectId();
        when(accountRepository.findById(from.getId())).thenReturn(Optional.of(from));
        when(accountRepository.findById(toId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operations.transfer(ownerId,
                new TransferRequest(from.getId().toHexString(), toId.toHexString(),
                        new BigDecimal("1.00"), "missing-destination"),
                from.getId(), toId, new BigDecimal("1.00")))
                .isInstanceOf(TransactionException.class)
                .hasMessage("Invalid fromAccount or toAccount");

        verify(transactionRepository, never()).findByIdempotencyKey(any());
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(ledgerRepository, never()).insert(any(Ledger.class));
    }

    @Test
    void inactiveAccountsCannotTransfer() {
        ObjectId ownerId = new ObjectId();
        Account from = account(new ObjectId(), ownerId, AccountStatus.FROZEN);
        Account to = account(new ObjectId(), new ObjectId(), AccountStatus.ACTIVE);
        when(accountRepository.findById(from.getId())).thenReturn(Optional.of(from));
        when(accountRepository.findById(to.getId())).thenReturn(Optional.of(to));
        when(transactionRepository.findByIdempotencyKey("inactive-key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operations.transfer(ownerId,
                request(from, to, "1.00", "inactive-key"),
                from.getId(), to.getId(), new BigDecimal("1.00")))
                .isInstanceOf(TransactionException.class)
                .hasMessage("Both fromAccount and toAccount must be ACTIVE to process transaction");
        verify(accountRepository, never()).touchActiveAccountOwnedBy(any(), any());
    }

    @Test
    void completedIdempotentRetryReturnsExistingTransactionWithoutWriting() {
        ObjectId ownerId = new ObjectId();
        Account from = account(new ObjectId(), ownerId, AccountStatus.ACTIVE);
        Account to = account(new ObjectId(), new ObjectId(), AccountStatus.ACTIVE);
        Transaction existing = transaction(from.getId(), to.getId(), "25.00", "repeat-key",
                TransactionStatus.COMPLETED);
        when(accountRepository.findById(from.getId())).thenReturn(Optional.of(from));
        when(accountRepository.findById(to.getId())).thenReturn(Optional.of(to));
        when(transactionRepository.findByIdempotencyKey("repeat-key")).thenReturn(Optional.of(existing));

        TransactionOutcome outcome = operations.transfer(ownerId,
                request(from, to, "25.00", "repeat-key"), from.getId(), to.getId(), new BigDecimal("25.00"));

        assertThat(outcome.status()).isEqualTo(HttpStatus.OK);
        assertThat(outcome.message()).isEqualTo("Transaction already processed");
        assertThat(outcome.transaction()).isSameAs(existing);
        verify(ledgerRepository, never()).insert(any(Ledger.class));
        verify(accountRepository, never()).touchActiveAccountOwnedBy(any(), any());
    }

    @Test
    void reusingIdempotencyKeyForDifferentPayloadConflicts() {
        ObjectId ownerId = new ObjectId();
        Account from = account(new ObjectId(), ownerId, AccountStatus.ACTIVE);
        Account to = account(new ObjectId(), new ObjectId(), AccountStatus.ACTIVE);
        Transaction existing = transaction(from.getId(), to.getId(), "10.00", "repeat-key",
                TransactionStatus.COMPLETED);
        when(accountRepository.findById(from.getId())).thenReturn(Optional.of(from));
        when(accountRepository.findById(to.getId())).thenReturn(Optional.of(to));
        when(transactionRepository.findByIdempotencyKey("repeat-key")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> operations.transfer(ownerId,
                request(from, to, "11.00", "repeat-key"), from.getId(), to.getId(), new BigDecimal("11.00")))
                .isInstanceOf(TransactionException.class)
                .extracting(exception -> ((TransactionException) exception).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void initialFundsUsesSystemAccountDebitAndTargetCreditWithoutPublicRoleInput() {
        ObjectId systemUserId = new ObjectId();
        Account system = account(new ObjectId(), systemUserId, AccountStatus.ACTIVE);
        Account target = account(new ObjectId(), new ObjectId(), AccountStatus.ACTIVE);
        when(accountRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(accountRepository.findByUser(systemUserId)).thenReturn(List.of(system));
        when(transactionRepository.findByIdempotencyKey("funding-key")).thenReturn(Optional.empty());
        when(accountRepository.touchActiveAccountOwnedBy(system.getId(), systemUserId)).thenReturn(true);
        when(accountRepository.touchActiveAccount(target.getId())).thenReturn(true);
        stubTransactionSave();

        TransactionOutcome outcome = operations.initialFunds(systemUserId,
                new InitialFundsRequest(target.getId().toHexString(), new BigDecimal("50.00"), "funding-key"),
                target.getId(), new BigDecimal("50.00"));

        assertThat(outcome.status()).isEqualTo(HttpStatus.CREATED);
        ArgumentCaptor<Ledger> entries = ArgumentCaptor.forClass(Ledger.class);
        verify(ledgerRepository, org.mockito.Mockito.times(2)).insert(entries.capture());
        assertThat(entries.getAllValues()).extracting(Ledger::getType)
                .containsExactly(LedgerEntryType.DEBIT, LedgerEntryType.CREDIT);
        assertThat(entries.getAllValues()).extracting(Ledger::getAccount)
                .containsExactly(system.getId(), target.getId());
        verify(ledgerRepository, never()).findByAccount(system.getId());
    }

    private void stubActiveTransfer(Account from, Account to, String idempotencyKey) {
        when(accountRepository.findById(from.getId())).thenReturn(Optional.of(from));
        when(accountRepository.findById(to.getId())).thenReturn(Optional.of(to));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(accountRepository.touchActiveAccountOwnedBy(from.getId(), from.getUser())).thenReturn(true);
        when(accountRepository.touchActiveAccount(to.getId())).thenReturn(true);
    }

    private void stubTransactionSave() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            if (transaction.getId() == null) {
                transaction.setId(new ObjectId());
            }
            return transaction;
        });
        when(ledgerRepository.insert(any(Ledger.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private TransferRequest request(Account from, Account to, String amount, String key) {
        return new TransferRequest(from.getId().toHexString(), to.getId().toHexString(),
                new BigDecimal(amount), key);
    }

    private Account account(ObjectId id, ObjectId userId, AccountStatus status) {
        Account account = new Account(userId);
        account.setId(id);
        account.setStatus(status);
        return account;
    }

    private Ledger ledger(ObjectId accountId, String amount, ObjectId transactionId, LedgerEntryType type) {
        return new Ledger(accountId, new BigDecimal(amount), transactionId, type);
    }

    private Transaction transaction(ObjectId fromId, ObjectId toId, String amount,
            String key, TransactionStatus status) {
        Transaction transaction = new Transaction();
        transaction.setId(new ObjectId());
        transaction.setFromAccount(fromId);
        transaction.setToAccount(toId);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setIdempotencyKey(key);
        transaction.setStatus(status);
        return transaction;
    }
}
