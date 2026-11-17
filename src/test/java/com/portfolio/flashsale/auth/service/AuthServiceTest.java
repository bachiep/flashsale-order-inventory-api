package com.portfolio.flashsale.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.flashsale.auth.domain.RefreshToken;
import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.dto.AuthResponse;
import com.portfolio.flashsale.auth.dto.LoginRequest;
import com.portfolio.flashsale.auth.dto.RegisterRequest;
import com.portfolio.flashsale.auth.repository.RefreshTokenRepository;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.auth.security.JwtService;
import com.portfolio.flashsale.common.exception.ConflictException;
import com.portfolio.flashsale.common.exception.UnauthorizedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private AuthService authService;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-02-01T10:00:00Z"), ZoneOffset.UTC);
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtService,
                clock,
                transactionManager
        );
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmailIgnoreCase("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("DUP@example.com", "Secret@123", "Dup User")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any());
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = new User("user@example.com", "encoded", "User", Role.ROLE_CUSTOMER, clock.instant());
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpass", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrongpass")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void registerHashesPasswordAndIssuesTokens() {
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Strong@123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 10L);
            return saved;
        });
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("jwt-access");
        when(jwtService.generateOpaqueRefreshToken()).thenReturn("raw-refresh");
        when(jwtService.hashRefreshToken("raw-refresh")).thenReturn("hashed-refresh");
        when(jwtService.getAccessExpirationSeconds()).thenReturn(900L);
        when(jwtService.getRefreshExpirationSeconds()).thenReturn(604800L);

        AuthResponse response = authService.register(new RegisterRequest("New@example.com", "Strong@123", "New User"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.accessToken()).isEqualTo("jwt-access");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh");
        assertThat(response.user().email()).isEqualTo("new@example.com");
        assertThat(response.user().role()).isEqualTo(Role.ROLE_CUSTOMER);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void refreshRevokesTokenFamilyOnReuseDetection() {
        User user = new User("user@example.com", "encoded", "User", Role.ROLE_CUSTOMER, clock.instant());
        ReflectionTestUtils.setField(user, "id", 5L);
        RefreshToken revokedToken = new RefreshToken(user, "hashed-old", clock.instant().plusSeconds(3600), clock.instant());
        revokedToken.revoke();

        when(jwtService.hashRefreshToken("old-raw")).thenReturn("hashed-old");
        when(refreshTokenRepository.findWithUserByTokenHash("hashed-old")).thenReturn(Optional.of(revokedToken));
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        assertThatThrownBy(() -> authService.refresh(new com.portfolio.flashsale.auth.dto.RefreshTokenRequest("old-raw")))
                .isInstanceOf(UnauthorizedException.class);

        verify(refreshTokenRepository).revokeAllActiveByUserId(5L);
    }
}
