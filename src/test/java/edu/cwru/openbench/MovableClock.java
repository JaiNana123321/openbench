package edu.cwru.openbench;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The Clock of every integration test: the system clock in the company zone until a test sets it,
 * so tests that never move time see the real time. A test that moves it ({@link #set}) resets it
 * when it ends ({@link #reset}), because the application context, and so this clock, is shared by
 * every test class. Every module injects it in place of the production Clock ({@link Config}).
 */
public final class MovableClock extends Clock {

    private final ZoneId zone;
    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    public MovableClock(ZoneId zone) {
        this.zone = zone;
    }

    /** From now on the Clock reads this instant, until it is set again or reset. */
    public void set(Instant instant) {
        fixed.set(instant);
    }

    /** Back to the system clock. */
    public void reset() {
        fixed.set(null);
    }

    @Override
    public Instant instant() {
        Instant at = fixed.get();
        return at == null ? Clock.system(zone).instant() : at;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        Instant at = fixed.get();
        return at == null ? Clock.system(other) : Clock.fixed(at, other);
    }

    /** Puts the movable Clock in the context ahead of the production one. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        MovableClock movableClock(@Value("${openbench.zone}") ZoneId zone) {
            return new MovableClock(zone);
        }
    }
}
