package com.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledger.model.Account;
import com.ledger.model.Ledger;
import com.ledger.model.LedgerEntryType;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.LedgerRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerRepository ledgerRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void createsDefaultAccountForAuthenticatedOwner() {
        ObjectId ownerId = new ObjectId();
        ObjectId accountId = new ObjectId();
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(accountId);
            return account;
        });

        Account account = accountService.createAccount(ownerId);

        assertThat(account.getId()).isEqualTo(accountId);
        assertThat(account.getUser()).isEqualTo(ownerId);
        assertThat(account.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(account.getCurrency()).isEqualTo("INR");
        assertThat(account.getVersion()).isZero();
        verify(accountRepository).save(argThat(saved -> saved.getUser().equals(ownerId)
                && saved.getStatus() == com.ledger.model.AccountStatus.ACTIVE
                && saved.getCurrency().equals("INR")));
    }

    @Test
    void listsAccountsUsingOnlyAuthenticatedOwnerId() {
        ObjectId ownerId = new ObjectId();
        when(accountRepository.findByUser(ownerId)).thenReturn(List.of(new Account(ownerId)));

        List<Account> accounts = accountService.getAccounts(ownerId);

        assertThat(accounts).hasSize(1);
        verify(accountRepository).findByUser(ownerId);
    }

    @Test
    void calculatesCreditsMinusDebitsUsingBigDecimal() {
        ObjectId ownerId = new ObjectId();
        ObjectId accountId = new ObjectId();
        when(accountRepository.findByIdAndUser(accountId, ownerId))
                .thenReturn(Optional.of(new Account(ownerId)));
        when(ledgerRepository.findByAccount(accountId)).thenReturn(List.of(
                ledger(accountId, "100.00", LedgerEntryType.CREDIT),
                ledger(accountId, "22.50", LedgerEntryType.DEBIT),
                ledger(accountId, "0.05", LedgerEntryType.CREDIT)));

        BigDecimal balance = accountService.getBalance(accountId.toHexString(), ownerId);

        assertThat(balance).isEqualByComparingTo("77.55");
    }

    @Test
    void returnsZeroForAnEmptyLedger() {
        ObjectId ownerId = new ObjectId();
        ObjectId accountId = new ObjectId();
        when(accountRepository.findByIdAndUser(accountId, ownerId))
                .thenReturn(Optional.of(new Account(ownerId)));
        when(ledgerRepository.findByAccount(accountId)).thenReturn(List.of());

        assertThat(accountService.getBalance(accountId.toHexString(), ownerId)).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void invalidIdsAreRejectedBeforeDatabaseQueries() {
        assertThatThrownBy(() -> accountService.getBalance("invalid-id", new ObjectId()))
                .isInstanceOf(InvalidAccountIdException.class);
        verify(accountRepository, never()).findByIdAndUser(any(), any());
        verify(ledgerRepository, never()).findByAccount(any());
    }

    @Test
    void missingOrUnownedAccountIsNotFoundAndLedgerIsNotRead() {
        ObjectId ownerId = new ObjectId();
        ObjectId accountId = new ObjectId();
        when(accountRepository.findByIdAndUser(accountId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getBalance(accountId.toHexString(), ownerId))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");
        verify(ledgerRepository, never()).findByAccount(any());
    }

    private Ledger ledger(ObjectId accountId, String amount, LedgerEntryType type) {
        return new Ledger(accountId, new BigDecimal(amount), new ObjectId(), type);
    }
}
