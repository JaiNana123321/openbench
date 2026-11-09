package edu.cwru.openbench.jobs;

import edu.cwru.openbench.requests.RequestLifecycleService;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The work that falls due as time passes, polled every 60 s after the last run ended (the
 * design's Jobs module, SRS PE-jobs: within five minutes of the due time): waiting lines whose window has
 * started leave the waitlist. Runs as the system; each due item is its own transaction in the
 * owning module, and a failure is logged and never stops the rest of the run.
 */
@Component
public class DueWorkJob {

    private static final Logger log = LoggerFactory.getLogger(DueWorkJob.class);

    private final RequestLifecycleService lifecycle;
    private final Clock clock;

    DueWorkJob(RequestLifecycleService lifecycle, Clock clock) {
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    /** One run at the Clock's current instant. Tests call it directly; scheduling is off there. */
    @Scheduled(fixedDelay = 60_000)
    public void run() {
        Instant now = clock.instant();
        SystemContext.run(() -> {
            // Objection Window lapses (#5) go here, before the expiry, as the design orders them:
            // for each Request of lifecycle.lapsedWindows(now), lifecycle.onWindowLapse(request) in
            // a try/catch that logs the Request id and moves on.
            try {
                int expired = lifecycle.expireWaitingLines(now);
                log.info("event=job job=due_work at={} waitlist_expired={}", now, expired);
            } catch (RuntimeException e) {
                log.error("event=job_failed job=due_work step=waitlist_expiry at={} cause=\"{}\"", now, e.getMessage(), e);
            }
        });
    }
}
