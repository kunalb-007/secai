package com.secai.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Organization name is required")
        String organizationName,

        @NotBlank @Email(message = "Valid email required")
        String email,

        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
        String password
) {}