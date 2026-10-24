package com.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledger.config.JwtProperties;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void issuesAndVerifiesOriginalUserIdClaim() {
        JwtService service = new JwtService(properties("jwt-test-secret-with-at-least-32-bytes"));
        ObjectId userId = new ObjectId();

        String token = service.issueToken(userId);

        assertThat(service.verifyAndGetUserId(token)).isEqualTo(userId.toHexString());
    }

    @Test
    void rejectsSecretsShorterThanHmac256Requirement() {
        assertThatThrownBy(() -> new JwtService(properties("too-short")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT_SECRET must contain at least 32 UTF-8 bytes");
    }

    private JwtProperties properties(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        return properties;
    }
}
