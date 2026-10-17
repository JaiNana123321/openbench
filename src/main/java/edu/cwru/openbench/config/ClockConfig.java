package edu.cwru.openbench.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one {@link Clock} every module injects to read the time, in the company time zone. Code never
 * calls the no-argument {@code now()} methods (the ArchUnit test forbids them), so tests can move
 * time by replacing this bean.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    Clock clock(@Value("${openbench.zone}") ZoneId zone) {
        return Clock.system(zone);
    }
}
