package com.ledger.account;

import com.ledger.model.Account;
import com.ledger.model.Ledger;
import com.ledger.model.LedgerEntryType;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.LedgerRepository;
import java.math.BigDecimal;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerRepository ledgerRepository;

    public AccountService(AccountRepository accountRepository, LedgerRepository ledgerRepository) {
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
    }

    public Account createAccount(ObjectId ownerId) {
        return accountRepository.save(new Account(ownerId));
    }

    public List<Account> getAccounts(ObjectId ownerId) {
        return accountRepository.findByUser(ownerId);
    }

    public BigDecimal getBalance(String accountIdValue, ObjectId ownerId) {
        if (!ObjectId.isValid(accountIdValue)) {
            throw new InvalidAccountIdException();
        }

        ObjectId accountId = new ObjectId(accountIdValue);
        accountRepository.findByIdAndUser(accountId, ownerId)
                .orElseThrow(AccountNotFoundException::new);

        return ledgerRepository.findByAccount(accountId).stream()
                .map(AccountService::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal signedAmount(Ledger entry) {
        if (entry.getAmount() == null || entry.getType() == null) {
            return BigDecimal.ZERO;
        }
        if (entry.getType() == LedgerEntryType.CREDIT) {
            return entry.getAmount();
        }
        if (entry.getType() == LedgerEntryType.DEBIT) {
            return entry.getAmount().negate();
        }
        return BigDecimal.ZERO;
    }
}
