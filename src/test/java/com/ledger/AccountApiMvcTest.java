package com.ledger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.model.Account;
import com.ledger.model.AccountStatus;
import com.ledger.model.Ledger;
import com.ledger.model.LedgerEntryType;
import com.ledger.model.User;
import com.ledger.repository.AccountRepository;
import com.ledger.repository.BlacklistedTokenRepository;
import com.ledger.repository.LedgerRepository;
import com.ledger.repository.UserRepository;
import com.ledger.security.JwtService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "jwt.secret=phase-three-test-secret-with-at-least-32-bytes",
        "spring.data.mongodb.auto-index-creation=false",
        "auth.cookie.secure=false",
        "debug=false",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
class AccountApiMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private BlacklistedTokenRepository blacklistedTokenRepository;

    @MockitoBean
    private AccountRepository accountRepository;

    @MockitoBean
    private LedgerRepository ledgerRepository;

    @Test
    void createUsesAuthenticatedOwnerAndOriginalAccountDefaults() throws Exception {
        User caller = authenticatedCaller();
        ObjectId accountId = new ObjectId();
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(accountId);
            account.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            account.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return account;
        });

        mockMvc.perform(post("/api/accounts/")
                        .header("Authorization", "Bearer " + jwtService.issueToken(caller.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user":"000000000000000000000001","status":"CLOSED","currency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account._id").value(accountId.toHexString()))
                .andExpect(jsonPath("$.account.user").value(caller.getId().toHexString()))
                .andExpect(jsonPath("$.account.status").value("ACTIVE"))
                .andExpect(jsonPath("$.account.currency").value("INR"))
                .andExpect(jsonPath("$.account.__v").value(0));

        verify(accountRepository).save(org.mockito.ArgumentMatchers.argThat(account ->
                account.getUser().equals(caller.getId())
                        && account.getStatus() == AccountStatus.ACTIVE
                        && "INR".equals(account.getCurrency())));
    }

    @Test
    void listsOnlyAccountsForAuthenticatedOwner() throws Exception {
        User caller = authenticatedCaller();
        Account ownAccount = account(new ObjectId(), caller.getId());
        when(accountRepository.findByUser(caller.getId())).thenReturn(List.of(ownAccount));

        mockMvc.perform(get("/api/accounts/")
                        .header("Authorization", "Bearer " + jwtService.issueToken(caller.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.accounts[0]._id").value(ownAccount.getId().toHexString()))
                .andExpect(jsonPath("$.accounts[0].user").value(caller.getId().toHexString()));

        verify(accountRepository).findByUser(caller.getId());
    }

    @Test
    void balanceUsesLedgerCreditMinusDebit() throws Exception {
        User caller = authenticatedCaller();
        ObjectId accountId = new ObjectId();
        when(accountRepository.findByIdAndUser(accountId, caller.getId()))
                .thenReturn(Optional.of(account(accountId, caller.getId())));
        when(ledgerRepository.findByAccount(accountId)).thenReturn(List.of(
                ledger(accountId, "125.00", LedgerEntryType.CREDIT),
                ledger(accountId, "25.25", LedgerEntryType.DEBIT)));

        mockMvc.perform(get("/api/accounts/balance/{accountId}", accountId.toHexString())
                        .header("Authorization", "Bearer " + jwtService.issueToken(caller.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toHexString()))
                .andExpect(jsonPath("$.balance").value(99.75));
    }

    @Test
    void emptyLedgerReturnsZeroBalance() throws Exception {
        User caller = authenticatedCaller();
        ObjectId accountId = new ObjectId();
        when(accountRepository.findByIdAndUser(accountId, caller.getId()))
                .thenReturn(Optional.of(account(accountId, caller.getId())));
        when(ledgerRepository.findByAccount(accountId)).thenReturn(List.of());

        mockMvc.perform(get("/api/accounts/balance/{accountId}", accountId.toHexString())
                        .header("Authorization", "Bearer " + jwtService.issueToken(caller.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void invalidAccountIdReturnsBadRequest() throws Exception {
        User caller = authenticatedCaller();

        mockMvc.perform(get("/api/accounts/balance/{accountId}", "invalid-id")
                        .header("Authorization", "Bearer " + jwtService.issueToken(caller.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid account ID"));
    }

    @Test
    void nonexistentAndOtherUsersAccountsReturnSameNotFoundResponse() throws Exception {
        User caller = authenticatedCaller();
        ObjectId accountId = new ObjectId();
        when(accountRepository.findByIdAndUser(accountId, caller.getId())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/accounts/balance/{accountId}", accountId.toHexString())
                        .header("Authorization", "Bearer " + jwtService.issueToken(caller.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found"));

        org.mockito.Mockito.verifyNoInteractions(ledgerRepository);
    }

    @Test
    void unauthenticatedAccessIsRejected() throws Exception {
        mockMvc.perform(get("/api/accounts/"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized access, token is missing"));
    }

    @Test
    void malformedJsonAndDatabaseErrorsUseSafeResponses() throws Exception {
        User caller = authenticatedCaller();
        String token = jwtService.issueToken(caller.getId());

        mockMvc.perform(post("/api/accounts/")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"));

        when(accountRepository.findByUser(caller.getId()))
                .thenThrow(new DataAccessResourceFailureException("private database detail"));
        var result = mockMvc.perform(get("/api/accounts/")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unable to complete request"))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .doesNotContain("private database detail");
    }

    private User authenticatedCaller() {
        User caller = new User("account-owner@example.com", "Account Owner", "unused-password-value", false);
        caller.setId(new ObjectId());
        String token = jwtService.issueToken(caller.getId());
        when(blacklistedTokenRepository.existsByToken(token)).thenReturn(false);
        when(userRepository.findById(caller.getId())).thenReturn(Optional.of(caller));
        return caller;
    }

    private Account account(ObjectId id, ObjectId ownerId) {
        Account account = new Account(ownerId);
        account.setId(id);
        account.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        account.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return account;
    }

    private Ledger ledger(ObjectId accountId, String amount, LedgerEntryType type) {
        return new Ledger(accountId, new BigDecimal(amount), new ObjectId(), type);
    }
}
