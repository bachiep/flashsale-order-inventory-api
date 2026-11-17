package com.portfolio.flashsale.common.config;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.catalog.repository.ProductRepository;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DevDataSeederTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private FlashVoucherRepository voucherRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private DevDataSeeder seeder;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-01T09:00:00Z"), ZoneOffset.UTC);
        seeder = new DevDataSeeder(userRepository, productRepository, voucherRepository, passwordEncoder, clock);
    }

    @Test
    void seedsUsersProductsAndVouchersWhenDatabaseIsEmpty() {
        when(userRepository.count()).thenReturn(0L);
        when(productRepository.count()).thenReturn(0L);
        when(voucherRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("Admin@1234")).thenReturn("enc-admin");
        when(passwordEncoder.encode("Demo@1234")).thenReturn("enc-demo");

        seeder.run(null);

        verify(userRepository).saveAll(anyList());
        verify(productRepository).saveAll(anyList());
        verify(voucherRepository).saveAll(anyList());
    }

    @Test
    void skipsSeedingWhenUsersAlreadyExist() {
        when(userRepository.count()).thenReturn(3L);

        seeder.run(null);

        verify(userRepository, never()).saveAll(anyList());
        verify(productRepository, never()).saveAll(anyList());
        verify(voucherRepository, never()).saveAll(anyList());
    }
}
