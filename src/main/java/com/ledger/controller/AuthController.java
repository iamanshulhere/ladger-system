package com.ledger.controller;

import com.ledger.auth.AuthResult;
import com.ledger.auth.AuthService;
import com.ledger.config.JwtProperties;
import com.ledger.controller.dto.AuthResponse;
import com.ledger.controller.dto.AuthUserResponse;
import com.ledger.controller.dto.LoginRequest;
import com.ledger.controller.dto.MessageResponse;
import com.ledger.controller.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String TOKEN_COOKIE = "token";

    private final AuthService authService;
    private final boolean secureCookie;
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService,
            @Value("${auth.cookie.secure:true}") boolean secureCookie,
            JwtProperties jwtProperties) {
        this.authService = authService;
        this.secureCookie = secureCookie;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authService.register(request.email(), request.password(), request.name());
        return authenticatedResponse(result, 201);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request.email(), request.password());
        return authenticatedResponse(result, 200);
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(HttpServletRequest request) {
        String token = tokenFromCookie(request).orElseGet(() -> bearerToken(request).orElse(null));
        authService.logout(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildCookie("", Duration.ZERO).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new MessageResponse("User logged out successfully"));
    }

    private ResponseEntity<AuthResponse> authenticatedResponse(AuthResult result, int status) {
        AuthUserResponse user = new AuthUserResponse(
                result.user().getId().toHexString(), result.user().getEmail(), result.user().getName());
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE,
                        buildCookie(result.token(), Duration.ofDays(jwtProperties.getExpirationDays())).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthResponse(user));
    }

    private ResponseCookie buildCookie(String value, Duration maxAge) {
        return ResponseCookie.from(TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private Optional<String> tokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> TOKEN_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private Optional<String> bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String bearer = authorization.substring(7).trim();
        return bearer.isBlank() ? Optional.empty() : Optional.of(bearer);
    }
}
