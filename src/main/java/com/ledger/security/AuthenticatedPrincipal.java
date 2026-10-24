package com.ledger.security;

import org.bson.types.ObjectId;

public record AuthenticatedPrincipal(ObjectId userId, String email, String name, boolean systemUser) {
}
