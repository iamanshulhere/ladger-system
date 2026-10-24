package com.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledger.model.BlacklistedToken;
import com.ledger.model.User;
import com.ledger.repository.BlacklistedTokenRepository;
import com.ledger.repository.UserRepository;
import com.ledger.security.JwtService;
import com.ledger.transaction.TransactionOutcome;
import com.ledger.transaction.TransactionService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "jwt.secret=phase-two-test-secret-with-at-least-32-bytes",
        "spring.data.mongodb.auto-index-creation=false",
        "auth.cookie.secure=false",
        "debug=false",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
@Import(AuthFlowMvcTest.ProtectedTestEndpoints.class)
class AuthFlowMvcTest {

    private static final String JWT_SECRET = "phase-two-test-secret-with-at-least-32-bytes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private BlacklistedTokenRepository blacklistedTokenRepository;

    @MockitoBean
    private TransactionService transactionService;

    @Test
    void registrationCreatesHashedUserAndIssuesJwtOnlyInHttpOnlyCookie() throws Exception {
        ObjectId id = new ObjectId();
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(id);
            return user;
        });

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":" Alice@Example.com ","password":"correct-horse","name":"Alice","systemUser":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user._id").value(id.toHexString()))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.name").value("Alice"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(cookie().httpOnly("token", true))
                .andExpect(cookie().secure("token", false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("correct-horse");
        String token = result.getResponse().getCookie("token").getValue();
        assertThat(body).doesNotContain(token);
        assertThat(jwtService.verifyAndGetUserId(token)).isEqualTo(id.toHexString());
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("SameSite=Lax", "Path=/", "HttpOnly");
        verify(userRepository).save(argThat(user -> user.getEmail().equals("alice@example.com")
                && passwordEncoder.matches("correct-horse", user.getPassword())
                && !user.getPassword().equals("correct-horse")
                && !user.isSystemUser()));
    }

    @Test
    void duplicateRegistrationReturnsOriginal422Body() throws Exception {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"correct-horse","name":"Alice"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("User already exists with email."))
                .andExpect(jsonPath("$.status").value("failed"));
    }

    @Test
    void uniqueIndexRaceReturnsSameDuplicateResponse() throws Exception {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenThrow(
                new org.springframework.dao.DuplicateKeyException("duplicate key"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"correct-horse","name":"Alice"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("User already exists with email."))
                .andExpect(jsonPath("$.status").value("failed"));
    }

    @Test
    void loginVerifiesBcryptPasswordAndIssuesJwtOnlyInCookie() throws Exception {
        User user = user(new ObjectId(), false, "correct-horse");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ALICE@example.com","password":"correct-horse"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user._id").value(user.getId().toHexString()))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(cookie().httpOnly("token", true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("correct-horse", user.getPassword());
        String token = result.getResponse().getCookie("token").getValue();
        assertThat(body).doesNotContain(token);
        assertThat(jwtService.verifyAndGetUserId(token)).isEqualTo(user.getId().toHexString());
    }

    @Test
    void incorrectPasswordUsesGeneric401Response() throws Exception {
        User user = user(new ObjectId(), false, "correct-horse");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email or password is INVALID"));

        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"unknown@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email or password is INVALID"));
    }

    @Test
    void missingOrMalformedCredentialsAreRejectedAsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized access, token is missing"));
    }

    @Test
    void bearerTokenAuthenticatesProtectedEndpoint() throws Exception {
        User user = user(new ObjectId(), false, "correct-horse");
        String token = jwtService.issueToken(user.getId());
        stubValidToken(token, user);

        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("alice@example.com"));
    }

    @Test
    void cookieTokenAuthenticatesProtectedEndpoint() throws Exception {
        User user = user(new ObjectId(), false, "correct-horse");
        String token = jwtService.issueToken(user.getId());
        stubValidToken(token, user);

        mockMvc.perform(get("/api/test/protected").cookie(new Cookie("token", token)))
                .andExpect(status().isOk())
                .andExpect(content().string("alice@example.com"));
    }

    @Test
    void malformedAndExpiredTokensAreRejected() throws Exception {
        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized access, token is invalid"));

        String expired = expiredToken(new ObjectId());
        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized access, token is invalid"));
    }

    @Test
    void invalidSignatureAndMissingUserIdClaimAreRejected() throws Exception {
        String wrongSignature = Jwts.builder()
                .claim("userId", new ObjectId().toHexString())
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor("another-test-secret-long-enough-for-hmac".getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + wrongSignature))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized access, token is invalid"));

        String missingUserId = Jwts.builder()
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + missingUserId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized access, token is invalid"));
    }

    @Test
    void blacklistedTokenIsRejectedBeforeUserAuthentication() throws Exception {
        String token = jwtService.issueToken(new ObjectId());
        when(blacklistedTokenRepository.existsByToken(token)).thenReturn(true);

        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized access, token is invalid"));
    }

    @Test
    void authenticationDatabaseFailureReturnsGenericServiceUnavailableResponse() throws Exception {
        String token = jwtService.issueToken(new ObjectId());
        when(blacklistedTokenRepository.existsByToken(token)).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("private database detail"));

        mockMvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Authentication service unavailable"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void logoutBlacklistsSuppliedBearerTokenAndClearsCookie() throws Exception {
        String token = jwtService.issueToken(new ObjectId());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User logged out successfully"))
                .andExpect(cookie().httpOnly("token", true))
                .andExpect(cookie().maxAge("token", 0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")));

        verify(blacklistedTokenRepository).save(argThat(entry -> token.equals(entry.getToken())));

        String cookieToken = jwtService.issueToken(new ObjectId());
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("token", cookieToken)))
                .andExpect(status().isOk());
        verify(blacklistedTokenRepository).save(argThat(entry -> cookieToken.equals(entry.getToken())));
    }

    @Test
    void logoutWithoutTokenRemainsSuccessfulAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User logged out successfully"))
                .andExpect(cookie().maxAge("token", 0));
    }

    @Test
    void logoutDoesNotStoreMalformedToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User logged out successfully"));

        verify(blacklistedTokenRepository, never()).save(any(BlacklistedToken.class));
    }

    @Test
    void onlyDatabaseSystemUserCanAccessSystemTransactionRoute() throws Exception {
        User regularUser = user(new ObjectId(), false, "correct-horse");
        String regularToken = jwtService.issueToken(regularUser.getId());
        stubValidToken(regularToken, regularUser);
        mockMvc.perform(post("/api/transactions/system/initial-funds")
                        .header("Authorization", "Bearer " + regularToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toAccount":"0123456789abcdef01234567","amount":1,"idempotencyKey":"test-key"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Forbidden access, not a system user"));

        User systemUser = user(new ObjectId(), true, "correct-horse");
        String systemToken = jwtService.issueToken(systemUser.getId());
        stubValidToken(systemToken, systemUser);
        when(transactionService.initialFunds(org.mockito.ArgumentMatchers.eq(systemUser.getId()), any()))
                .thenReturn(new TransactionOutcome(HttpStatus.CREATED,
                        "Initial funds transaction completed successfully", null));
        mockMvc.perform(post("/api/transactions/system/initial-funds")
                        .header("Authorization", "Bearer " + systemToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toAccount":"0123456789abcdef01234567","amount":1,"idempotencyKey":"test-key"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Initial funds transaction completed successfully"));
    }

    private User user(ObjectId id, boolean systemUser, String rawPassword) {
        User user = new User("alice@example.com", "Alice", passwordEncoder.encode(rawPassword), systemUser);
        user.setId(id);
        return user;
    }

    private void stubValidToken(String token, User user) {
        when(blacklistedTokenRepository.existsByToken(token)).thenReturn(false);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    private String expiredToken(ObjectId id) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("userId", id.toHexString())
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    @RestController
    static class ProtectedTestEndpoints {
        @GetMapping("/api/test/protected")
        String protectedEndpoint(org.springframework.security.core.Authentication authentication) {
            return ((com.ledger.security.AuthenticatedPrincipal) authentication.getPrincipal()).email();
        }

    }
}
