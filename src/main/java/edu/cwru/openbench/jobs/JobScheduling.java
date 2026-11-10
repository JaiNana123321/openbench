package edu.cwru.openbench.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns the jobs on: the {@code @Scheduled} methods, and the rollover's catch-up once the
 * application is ready. {@code openbench.jobs.enabled=false} turns both off; the tests do so and call
 * each job's {@code run()} directly with a movable Clock.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "openbench.jobs.enabled", havingValue = "true", matchIfMissing = true)
class JobScheduling {

    private final PeriodRolloverJob rollover;

    JobScheduling(PeriodRolloverJob rollover) {
        this.rollover = rollover;
    }

    @EventListener(ApplicationReadyEvent.class)
    void catchUpOnStart() {
        rollover.catchUp();
    }
}
