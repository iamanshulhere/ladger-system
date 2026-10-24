package com.ledger.security;

import com.ledger.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final javax.crypto.SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secret = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
    }

    public String issueToken(ObjectId userId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getExpirationDays(), ChronoUnit.DAYS);
        return Jwts.builder()
                .claim("userId", userId.toHexString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public String verifyAndGetUserId(String token) throws JwtException {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Object claim = claims.get("userId");
        if (!(claim instanceof String userId)
                || !ObjectId.isValid(userId)
                || claims.getExpiration() == null) {
            throw new IllegalArgumentException("JWT is missing required claims");
        }
        return userId;
    }
}
