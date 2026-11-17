package com.portfolio.flashsale.auth.dto;

import com.portfolio.flashsale.auth.domain.Role;
import java.time.Instant;

public record UserProfileResponse(
        Long id,
        String email,
        String fullName,
        Role role,
        Instant createdAt
) {
}
