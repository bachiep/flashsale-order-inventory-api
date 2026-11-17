package com.portfolio.flashsale.auth.dto;

public record AuthResponse(
        String tokenType,
        String accessToken,
        long expiresInSeconds,
        String refreshToken,
        UserProfileResponse user
) {
    public static AuthResponse bearer(
            String accessToken,
            long expiresInSeconds,
            String refreshToken,
            UserProfileResponse user
    ) {
        return new AuthResponse("Bearer", accessToken, expiresInSeconds, refreshToken, user);
    }
}
