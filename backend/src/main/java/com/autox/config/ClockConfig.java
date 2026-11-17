package com.autox.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's clock, for code whose behaviour depends on time passing: the system's,
 * in UTC. A test that has to step time forward provides its own as {@code @Primary}, rather
 * than sleeping through real windows, which a loaded machine stretches.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
