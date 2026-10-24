package com.ledger.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.model.User;
import com.ledger.repository.BlacklistedTokenRepository;
import com.ledger.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final BlacklistedTokenRepository blacklistedTokenRepository;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService,
            UserRepository userRepository,
            BlacklistedTokenRepository blacklistedTokenRepository,
            ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.blacklistedTokenRepository = blacklistedTokenRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return ("GET".equals(method) && "/".equals(path))
                || ("POST".equals(method) && ("/api/auth/register".equals(path)
                        || "/api/auth/login".equals(path)
                        || "/api/auth/logout".equals(path)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Optional<String> tokenValue = extractToken(request);
        if (tokenValue.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = tokenValue.get();
        User user;
        try {
            if (blacklistedTokenRepository.existsByToken(token)) {
                writeUnauthorized(response, "Unauthorized access, token is invalid");
                return;
            }

            String userIdClaim = jwtService.verifyAndGetUserId(token);
            user = userRepository.findById(new ObjectId(userIdClaim)).orElse(null);
            if (user == null) {
                writeUnauthorized(response, "Unauthorized access, token is invalid");
                return;
            }
        } catch (JwtException | IllegalArgumentException exception) {
            writeUnauthorized(response, "Unauthorized access, token is invalid");
            return;
        } catch (DataAccessException exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    Map.of("message", "Authentication service unavailable"));
            return;
        }

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                user.getId(), user.getEmail(), user.getName(), user.isSystemUser());
        List<SimpleGrantedAuthority> authorities = user.isSystemUser()
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_SYSTEM_USER"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("token".equals(cookie.getName()) && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return Optional.of(cookie.getValue());
                }
            }
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return Optional.empty();
        }
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearer = authorization.substring(7).trim();
            return bearer.isEmpty() ? Optional.of("") : Optional.of(bearer);
        }
        return Optional.of("");
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("message", message));
    }
}
