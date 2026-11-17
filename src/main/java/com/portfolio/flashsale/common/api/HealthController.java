package com.portfolio.flashsale.common.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "Service and database health status")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final Clock clock;

    public HealthController(JdbcTemplate jdbcTemplate, Environment environment, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Check API and database connectivity")
    public ResponseEntity<HealthResponse> health() {
        Integer dbProbe = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        String dbStatus = (dbProbe != null && dbProbe == 1) ? "UP" : "UNKNOWN";
        List<String> profiles = List.of(environment.getActiveProfiles());
        if (profiles.isEmpty()) {
            profiles = List.of(environment.getDefaultProfiles());
        }
        return ResponseEntity.ok(new HealthResponse(
                "UP",
                "flashsale-order-inventory-api",
                profiles,
                dbStatus,
                clock.instant()
        ));
    }

    public record HealthResponse(
            String status,
            String service,
            List<String> profiles,
            String database,
            Instant timestamp
    ) {
    }
}
