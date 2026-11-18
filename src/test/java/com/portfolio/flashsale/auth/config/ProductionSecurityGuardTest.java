package com.portfolio.flashsale.auth.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.flashsale.common.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecurityGuardTest {

    @Test
    void allowsDefaultSecretOutsideProdProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                env,
                propsWithSecret("dev-only-change-me-before-production-use-at-least-32-chars")
        );

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsPlaceholderSecretInProdProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                env,
                propsWithSecret("dev-only-change-me-before-production-use-at-least-32-chars")
        );

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder JWT_SECRET");
    }

    @Test
    void rejectsShortSecretInProdProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        ProductionSecurityGuard guard = new ProductionSecurityGuard(env, propsWithSecret("too-short-secret"));

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void acceptsStrongSecretInProdProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        ProductionSecurityGuard guard = new ProductionSecurityGuard(
                env,
                propsWithSecret("K8mP9vX2qR7zW4nB6yT1cF5hJ0dL3sA9eG2uM8pQ4wZ6")
        );

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    private AppProperties propsWithSecret(String secret) {
        return new AppProperties(
                new AppProperties.Jwt(secret, 900, 604800),
                new AppProperties.Order(900),
                new AppProperties.Scheduler(new AppProperties.Scheduler.ReservationExpiry(false, 30000, 50)),
                new AppProperties.Seed(false)
        );
    }
}
