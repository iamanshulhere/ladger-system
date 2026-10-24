package com.ledger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.controller.dto.InitialFundsRequest;
import com.ledger.controller.dto.TransferRequest;
import com.ledger.model.Transaction;
import com.ledger.model.TransactionStatus;
import com.ledger.model.User;
import com.ledger.repository.BlacklistedTokenRepository;
import com.ledger.repository.UserRepository;
import com.ledger.security.JwtService;
import com.ledger.transaction.TransactionOutcome;
import com.ledger.transaction.TransactionService;
import java.math.BigDecimal;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "jwt.secret=transaction-api-test-secret-at-least-32-bytes",
        "spring.data.mongodb.auto-index-creation=false",
        "auth.cookie.secure=false",
        "debug=false",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
class TransactionApiMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private BlacklistedTokenRepository blacklistedTokenRepository;

    @MockitoBean
    private TransactionService transactionService;

    @Test
    void transferEndpointUsesAuthenticatedUserAndReturnsOriginalEnvelope() throws Exception {
        User user = user(false);
        String token = authenticate(user);
        ObjectId fromId = new ObjectId();
        ObjectId toId = new ObjectId();
        Transaction transaction = transaction(fromId, toId, "12.50", "wire-key");
        when(transactionService.transfer(eq(user.getId()), any(TransferRequest.class)))
                .thenReturn(new TransactionOutcome(HttpStatus.CREATED,
                        "Transaction completed successfully", transaction));

        mockMvc.perform(post("/api/transactions/")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccount":"%s","toAccount":"%s","amount":12.5,"idempotencyKey":"wire-key","userId":"000000000000000000000001","systemUser":true}
                                """.formatted(fromId, toId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Transaction completed successfully"))
                .andExpect(jsonPath("$.transaction._id").value(transaction.getId().toHexString()))
                .andExpect(jsonPath("$.transaction.status").value("COMPLETED"))
                .andExpect(jsonPath("$.transaction.amount").value(12.5));

        verify(transactionService).transfer(eq(user.getId()), any(TransferRequest.class));
    }

    @Test
    void transferRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/transactions/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized access, token is missing"));
        verify(transactionService, never()).transfer(any(), any());
    }

    @Test
    void invalidTransferFieldsReturnOriginalRequiredMessage() throws Exception {
        User user = user(false);
        String token = authenticate(user);

        mockMvc.perform(post("/api/transactions/")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccount":"","toAccount":"%s","amount":0,"idempotencyKey":""}
                                """.formatted(new ObjectId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("FromAccount, toAccount, amount and idempotencyKey are required"));

        mockMvc.perform(post("/api/transactions/")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccount":"%s","toAccount":"%s","amount":0,"idempotencyKey":"zero-key"}
                                """.formatted(new ObjectId(), new ObjectId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Transaction amount must be greater than zero"));
        verify(transactionService, never()).transfer(any(), any());
    }

    @Test
    void initialFundsRequiresStoredSystemUserPrivilege() throws Exception {
        User normalUser = user(false);
        String normalToken = authenticate(normalUser);
        String body = """
                {"toAccount":"%s","amount":10,"idempotencyKey":"funds-key"}
                """.formatted(new ObjectId());

        mockMvc.perform(post("/api/transactions/system/initial-funds")
                        .header("Authorization", "Bearer " + normalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Forbidden access, not a system user"));
        verify(transactionService, never()).initialFunds(any(), any());

        User systemUser = user(true);
        String systemToken = authenticate(systemUser);
        when(transactionService.initialFunds(eq(systemUser.getId()), any(InitialFundsRequest.class)))
                .thenReturn(new TransactionOutcome(HttpStatus.CREATED,
                        "Initial funds transaction completed successfully", null));

        mockMvc.perform(post("/api/transactions/system/initial-funds")
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Initial funds transaction completed successfully"));
        verify(transactionService).initialFunds(eq(systemUser.getId()), any(InitialFundsRequest.class));
    }

    @Test
    void initialFundsValidationRunsAfterSystemAuthorization() throws Exception {
        User systemUser = user(true);
        String token = authenticate(systemUser);

        mockMvc.perform(post("/api/transactions/system/initial-funds")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toAccount\":\"000000000000000000000001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("toAccount, amount and idempotencyKey are required"));
        verify(transactionService, never()).initialFunds(any(), any());
    }

    private User user(boolean systemUser) {
        User user = new User("transaction-user@example.com", "Transaction User", "unit-test-password", systemUser);
        user.setId(new ObjectId());
        return user;
    }

    private String authenticate(User user) {
        String token = jwtService.issueToken(user.getId());
        when(blacklistedTokenRepository.existsByToken(token)).thenReturn(false);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return token;
    }

    private Transaction transaction(ObjectId from, ObjectId to, String amount, String key) {
        Transaction transaction = new Transaction();
        transaction.setId(new ObjectId());
        transaction.setFromAccount(from);
        transaction.setToAccount(to);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setIdempotencyKey(key);
        transaction.setStatus(TransactionStatus.COMPLETED);
        return transaction;
    }
}
