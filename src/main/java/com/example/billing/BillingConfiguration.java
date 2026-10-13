package com.example.billing;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BillingConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
