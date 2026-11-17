package com.portfolio.flashsale.auth.security;

import com.portfolio.flashsale.auth.domain.Role;

public record AuthenticatedUser(
        Long id,
        String email,
        Role role
) {
}
