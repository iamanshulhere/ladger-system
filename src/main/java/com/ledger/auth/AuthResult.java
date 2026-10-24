package com.ledger.auth;

import com.ledger.model.User;

public record AuthResult(User user, String token) {
}
