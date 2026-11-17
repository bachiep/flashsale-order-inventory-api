package com.portfolio.flashsale.common.config;

import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.catalog.domain.Product;
import com.portfolio.flashsale.catalog.repository.ProductRepository;
import com.portfolio.flashsale.voucher.domain.DiscountType;
import com.portfolio.flashsale.voucher.domain.FlashVoucher;
import com.portfolio.flashsale.voucher.repository.FlashVoucherRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(value = "app.seed.enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final FlashVoucherRepository voucherRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public DevDataSeeder(
            UserRepository userRepository,
            ProductRepository productRepository,
            FlashVoucherRepository voucherRepository,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.voucherRepository = voucherRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }

        Instant now = clock.instant();

        userRepository.saveAll(List.of(
                new User("admin@flashsale.local", passwordEncoder.encode("Admin@1234"), "FlashSale Admin", Role.ROLE_ADMIN, now),
                new User("demo@flashsale.local", passwordEncoder.encode("Demo@1234"), "Demo Customer", Role.ROLE_CUSTOMER, now),
                new User("demo2@flashsale.local", passwordEncoder.encode("Demo@1234"), "Second Demo Customer", Role.ROLE_CUSTOMER, now)
        ));

        if (productRepository.count() == 0) {
            productRepository.saveAll(List.of(
                    new Product("FS-KEY-75", "Keychron Pro Wireless 75%", "Hot-swappable mechanical keyboard with QMK/VIA support", "Peripherals", new BigDecimal("89.00"), true, 25, now),
                    new Product("FS-MOUSE-8K", "Pulsar 8K Ultralight Mouse", "49g symmetrical wireless gaming mouse", "Peripherals", new BigDecimal("69.00"), true, 30, now),
                    new Product("FS-ANC-PRO", "Studio ANC Wireless Earbuds", "Hybrid active noise cancellation with 32h battery", "Audio", new BigDecimal("79.50"), true, 40, now),
                    new Product("FS-MON-27Q", "UltraSharp 27 QHD 165Hz Monitor", "Fast IPS 1ms factory-calibrated display", "Monitors", new BigDecimal("299.00"), true, 10, now),
                    new Product("FS-NVME-1T", "Gen4 NVMe SSD 1TB", "PCIe 4.0 x4 7000MB/s read solid state drive", "Storage", new BigDecimal("84.00"), true, 20, now),
                    new Product("FS-HUB-USB4", "Aluminum 8-in-1 USB4 Dock", "4K60 HDMI, 100W PD passthrough, 2.5GbE", "Accessories", new BigDecimal("54.00"), true, 35, now)
            ));
        }

        if (voucherRepository.count() == 0) {
            Instant startsAt = now.minus(5, ChronoUnit.MINUTES);
            Instant endsAt = now.plus(24, ChronoUnit.HOURS);
            voucherRepository.saveAll(List.of(
                    new FlashVoucher("FLASH20", DiscountType.PERCENTAGE, new BigDecimal("20.00"), new BigDecimal("50.00"), 50, startsAt, endsAt, true, now),
                    new FlashVoucher("WELCOME15", DiscountType.FIXED_AMOUNT, new BigDecimal("15.00"), new BigDecimal("60.00"), 100, startsAt, endsAt, true, now)
            ));
        }

        log.info("Seeded default development users, catalog products, and active flash vouchers");
    }
}
