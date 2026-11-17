package com.portfolio.flashsale.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.common.config.AppProperties;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceTest {

    private static final String TEST_SECRET = "test-secret-key-with-more-than-32-bytes-length";

    @Test
    void generatesAndParsesValidAccessToken() {
        AtomicReference<Instant> current = new AtomicReference<>(Instant.parse("2026-02-01T10:00:00Z"));
        Clock clock = mutableClock(current);
        JwtService jwtService = new JwtService(appProperties(), clock);

        User user = new User("alice@example.com", "hashed", "Alice", Role.ROLE_CUSTOMER, current.get());
        ReflectionTestUtils.setField(user, "id", 42L);

        String token = jwtService.generateAccessToken(user);
        AuthenticatedUser parsed = jwtService.parseAccessToken(token);

        assertThat(parsed.id()).isEqualTo(42L);
        assertThat(parsed.email()).isEqualTo("alice@example.com");
        assertThat(parsed.role()).isEqualTo(Role.ROLE_CUSTOMER);
    }

    @Test
    void rejectsExpiredAccessTokenAccordingToClock() {
        AtomicReference<Instant> current = new AtomicReference<>(Instant.parse("2026-02-01T10:00:00Z"));
        Clock clock = mutableClock(current);
        JwtService jwtService = new JwtService(appProperties(), clock);

        User user = new User("bob@example.com", "hashed", "Bob", Role.ROLE_ADMIN, current.get());
        ReflectionTestUtils.setField(user, "id", 7L);

        String token = jwtService.generateAccessToken(user);
        current.set(current.get().plusSeconds(901));

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void rejectsTamperedToken() {
        Clock clock = Clock.fixed(Instant.parse("2026-02-01T10:00:00Z"), ZoneOffset.UTC);
        JwtService jwtService = new JwtService(appProperties(), clock);

        assertThatThrownBy(() -> jwtService.parseAccessToken("invalid.jwt.token"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void hashesRefreshTokensDeterministically() {
        Clock clock = Clock.fixed(Instant.parse("2026-02-01T10:00:00Z"), ZoneOffset.UTC);
        JwtService jwtService = new JwtService(appProperties(), clock);

        String raw = jwtService.generateOpaqueRefreshToken();
        assertThat(raw).hasSizeGreaterThanOrEqualTo(40);
        assertThat(jwtService.hashRefreshToken(raw))
                .hasSize(64)
                .isEqualTo(jwtService.hashRefreshToken(raw));
    }

    private AppProperties appProperties() {
        return new AppProperties(
                new AppProperties.Jwt(TEST_SECRET, 900, 604800),
                new AppProperties.Order(900),
                new AppProperties.Scheduler(new AppProperties.Scheduler.ReservationExpiry(false, 30000, 50)),
                new AppProperties.Seed(false)
        );
    }

    private Clock mutableClock(AtomicReference<Instant> ref) {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return ref.get();
            }
        };
    }
}
