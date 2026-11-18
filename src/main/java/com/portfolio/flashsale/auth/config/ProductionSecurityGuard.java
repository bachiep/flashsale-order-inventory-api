package com.portfolio.flashsale.auth.config;

import com.portfolio.flashsale.common.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fails fast on startup when the {@code prod} profile uses placeholder or undersized JWT secrets.
 */
@Component
public class ProductionSecurityGuard implements InitializingBean {

    private static final List<String> INSECURE_PREFIXES = List.of(
            "dev-only-",
            "docker-local-secret",
            "change-me"
    );

    private final Environment environment;
    private final AppProperties appProperties;

    public ProductionSecurityGuard(Environment environment, AppProperties appProperties) {
        this.environment = environment;
        this.appProperties = appProperties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        String secret = appProperties.jwt() != null ? appProperties.jwt().secret() : null;
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("Production profile requires JWT_SECRET of at least 32 bytes.");
        }
        String normalized = secret.trim().toLowerCase();
        for (String prefix : INSECURE_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                throw new IllegalStateException(
                        "Production profile cannot use a development or placeholder JWT_SECRET."
                );
            }
        }
    }
}
