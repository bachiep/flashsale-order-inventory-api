package com.portfolio.flashsale.voucher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.repository.RefreshTokenRepository;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.auth.security.JwtService;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import com.portfolio.flashsale.voucher.repository.VoucherClaimRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VoucherConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FlashVoucherRepository voucherRepository;

    @Autowired
    private VoucherClaimRepository claimRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private Clock clock;

    private String adminToken;

    @BeforeEach
    void setUp() {
        claimRepository.deleteAll();
        voucherRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(new User(
                "admin-voucher@flashsale.local",
                passwordEncoder.encode("Admin@1234"),
                "Voucher Admin",
                Role.ROLE_ADMIN,
                clock.instant()
        ));
        adminToken = jwtService.generateAccessToken(admin);
    }

    @Test
    void enforcesSingleClaimPerUserAndRbacOnVoucherCreation() throws Exception {
        Instant now = clock.instant();
        String payload = """
                {
                  "code": "SAVE20",
                  "discountType": "PERCENTAGE",
                  "discountValue": 20.00,
                  "minOrderAmount": 50.00,
                  "maxRedemptions": 10,
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "active": true
                }
                """.formatted(now.minusSeconds(60), now.plusSeconds(3600));

        User customer = userRepository.save(new User(
                "single-claim@flashsale.local",
                passwordEncoder.encode("Pass@1234"),
                "Customer One",
                Role.ROLE_CUSTOMER,
                now
        ));
        String customerToken = jwtService.generateAccessToken(customer);

        mockMvc.perform(post("/api/v1/admin/vouchers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/vouchers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SAVE20"))
                .andExpect(jsonPath("$.remainingQuota").value(10));

        mockMvc.perform(get("/api/v1/vouchers/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("SAVE20"));

        mockMvc.perform(post("/api/v1/vouchers/SAVE20/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CLAIMED"));

        mockMvc.perform(post("/api/v1/vouchers/SAVE20/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VOUCHER_ALREADY_CLAIMED"));

        mockMvc.perform(get("/api/v1/vouchers/my-claims")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].voucherCode").value("SAVE20"));
    }

    @Test
    void concurrent30ThreadsClaimingQuota5AllowsExactly5SuccessesAnd25Conflicts() throws Exception {
        Instant now = clock.instant();
        String createPayload = """
                {
                  "code": "FLASH5",
                  "discountType": "FIXED_AMOUNT",
                  "discountValue": 25.00,
                  "minOrderAmount": 0.00,
                  "maxRedemptions": 5,
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "active": true
                }
                """.formatted(now.minusSeconds(60), now.plusSeconds(3600));

        mockMvc.perform(post("/api/v1/admin/vouchers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated());

        int threadCount = 30;
        List<String> tokens = new ArrayList<>(threadCount);
        String encodedPw = passwordEncoder.encode("Pass@1234");
        for (int i = 0; i < threadCount; i++) {
            User customer = userRepository.save(new User(
                    "claim-racer-" + i + "@flashsale.local",
                    encodedPw,
                    "Racer " + i,
                    Role.ROLE_CUSTOMER,
                    now
            ));
            tokens.add(jwtService.generateAccessToken(customer));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>(threadCount);

        try {
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    MvcResult result = mockMvc.perform(post("/api/v1/vouchers/FLASH5/claim")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                            .andReturn();
                    return result.getResponse().getStatus();
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int createdCount = 0;
            int conflictCount = 0;
            for (Future<Integer> future : futures) {
                int statusCode = future.get(15, TimeUnit.SECONDS);
                if (statusCode == 201) {
                    createdCount++;
                } else if (statusCode == 409) {
                    conflictCount++;
                } else {
                    throw new AssertionError("Unexpected HTTP status during voucher contention: " + statusCode);
                }
            }

            assertThat(createdCount).isEqualTo(5);
            assertThat(conflictCount).isEqualTo(25);
        } finally {
            pool.shutdownNow();
        }

        FlashVoucher persisted = voucherRepository.findByCodeIgnoreCase("FLASH5").orElseThrow();
        assertThat(persisted.getClaimedCount()).isEqualTo(5);
        assertThat(claimRepository.count()).isEqualTo(5);
    }
}
