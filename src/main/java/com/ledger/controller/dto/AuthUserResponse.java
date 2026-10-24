package com.ledger.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthUserResponse(@JsonProperty("_id") String id, String email, String name) {
}
