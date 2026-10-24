package com.ledger.auth;

import com.ledger.model.BlacklistedToken;
import com.ledger.model.User;
import com.ledger.repository.BlacklistedTokenRepository;
import com.ledger.repository.UserRepository;
import com.ledger.security.JwtService;
import io.jsonwebtoken.JwtException;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final BlacklistedTokenRepository blacklistedTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
            BlacklistedTokenRepository blacklistedTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.blacklistedTokenRepository = blacklistedTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResult register(String email, String password, String name) {
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException();
        }

        User user = new User(normalizedEmail, name, passwordEncoder.encode(password), false);
        try {
            User saved = userRepository.save(user);
            return new AuthResult(saved, jwtService.issueToken(saved.getId()));
        } catch (DuplicateKeyException exception) {
            // The unique index remains the source of truth if two registrations race.
            throw new DuplicateEmailException();
        }
    }

    public AuthResult login(String email, String password) {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(InvalidCredentialsException::new);
        try {
            if (user.getPassword() == null || !passwordEncoder.matches(password, user.getPassword())) {
                throw new InvalidCredentialsException();
            }
        } catch (IllegalArgumentException exception) {
            throw new InvalidCredentialsException();
        }
        return new AuthResult(user, jwtService.issueToken(user.getId()));
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            jwtService.verifyAndGetUserId(token);
        } catch (JwtException | IllegalArgumentException exception) {
            return;
        }
        try {
            blacklistedTokenRepository.save(new BlacklistedToken(token));
        } catch (DuplicateKeyException ignored) {
            // Repeated logout is idempotent while the token remains blacklisted.
        }
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
