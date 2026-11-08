package com.roam.supplier;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupplierConfiguration {

    @Bean
    public FixtureScript fixtureScript() {
        return new FixtureScript();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
