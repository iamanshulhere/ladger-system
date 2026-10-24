package com.ledger.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
        String email,
        @NotBlank @Size(min = 6)
        String password,
        @NotBlank
        String name) {

    public RegisterRequest {
        if (email != null) {
            email = email.trim();
        }
    }
}
