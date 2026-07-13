package com.secai.dto.auth;

import java.util.UUID;

public record AuthResponse(
        String token,
        String email,
        UUID organizationId,
        String organizationName
) {}