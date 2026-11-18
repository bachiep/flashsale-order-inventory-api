package com.portfolio.flashsale;

import com.portfolio.flashsale.common.config.AppProperties;
import com.portfolio.flashsale.common.ratelimit.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, RateLimitProperties.class})
public class FlashsaleOrderInventoryApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashsaleOrderInventoryApiApplication.class, args);
    }
}
