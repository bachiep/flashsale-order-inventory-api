package com.portfolio.flashsale.auth.service;

import com.portfolio.flashsale.auth.domain.RefreshToken;
import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.dto.AuthResponse;
import com.portfolio.flashsale.auth.dto.LoginRequest;
import com.portfolio.flashsale.auth.dto.RefreshTokenRequest;
import com.portfolio.flashsale.auth.dto.RegisterRequest;
import com.portfolio.flashsale.auth.dto.UserProfileResponse;
import com.portfolio.flashsale.auth.repository.RefreshTokenRepository;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.auth.security.JwtService;
import com.portfolio.flashsale.common.exception.ConflictException;
import com.portfolio.flashsale.common.exception.ResourceNotFoundException;
import com.portfolio.flashsale.common.exception.UnauthorizedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.clock = clock;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate = template;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("EMAIL_ALREADY_EXISTS", "An account with this email already exists");
        }

        User user = new User(
                normalizedEmail,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                Role.ROLE_CUSTOMER,
                clock.instant()
        );
        User savedUser = userRepository.save(user);
        return issueTokenPair(savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password");
        }

        return issueTokenPair(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String tokenHash = jwtService.hashRefreshToken(request.refreshToken().trim());
        RefreshToken existingToken = refreshTokenRepository.findWithUserByTokenHash(tokenHash)
                .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid"));

        User user = existingToken.getUser();
        if (existingToken.isRevoked()) {
            // Reuse of a rotated/revoked refresh token revokes the user's active token family.
            requiresNewTransactionTemplate.executeWithoutResult(
                    status -> refreshTokenRepository.revokeAllActiveByUserId(user.getId())
            );
            throw new UnauthorizedException("REFRESH_TOKEN_REUSED", "Refresh token was already used or revoked");
        }

        Instant now = clock.instant();
        if (!existingToken.getExpiresAt().isAfter(now)) {
            existingToken.revoke();
            throw new UnauthorizedException("REFRESH_TOKEN_EXPIRED", "Refresh token has expired");
        }

        existingToken.revoke();
        return issueTokenPair(user);
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        String tokenHash = jwtService.hashRefreshToken(request.refreshToken().trim());
        refreshTokenRepository.findWithUserByTokenHash(tokenHash)
                .ifPresent(RefreshToken::revoke);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found"));
        return toUserProfile(user);
    }

    private AuthResponse issueTokenPair(User user) {
        Instant now = clock.instant();
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = jwtService.generateOpaqueRefreshToken();
        String refreshTokenHash = jwtService.hashRefreshToken(rawRefreshToken);

        RefreshToken refreshToken = new RefreshToken(
                user,
                refreshTokenHash,
                now.plusSeconds(jwtService.getRefreshExpirationSeconds()),
                now
        );
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.bearer(
                accessToken,
                jwtService.getAccessExpirationSeconds(),
                rawRefreshToken,
                toUserProfile(user)
        );
    }

    private UserProfileResponse toUserProfile(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getCreatedAt()
        );
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
