package com.sahilprojects.bettingApp.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Services take a Clock instead of calling Instant.now(), so deadlines and start times can be tested. */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
