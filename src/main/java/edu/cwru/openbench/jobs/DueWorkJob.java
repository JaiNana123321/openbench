package edu.cwru.openbench.jobs;

import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.requests.RequestLifecycleService;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The work that falls due as time passes, polled every 60 s after the last run ended (the
 * design's Jobs module, SRS PE-jobs: within five minutes of the due time): Objection Windows that
 * lapsed with approvals outstanding, then waiting lines whose window has started leave the waitlist. Runs as the system; each due item is its own transaction in the
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
            // Objection Window lapses first, then the waitlist expiry, as the design orders them.
            int lapsed = 0;
            try {
                for (RequestId request : lifecycle.lapsedWindows(now)) {
                    try {
                        lifecycle.onWindowLapse(request);
                        lapsed++;
                    } catch (RuntimeException e) {
                        log.error("event=job_failed job=due_work step=window_lapse request={} cause=\"{}\"",
                                request.value(), e.getMessage(), e);
                    }
                }
            } catch (RuntimeException e) {
                log.error("event=job_failed job=due_work step=window_lapse at={} cause=\"{}\"", now, e.getMessage(), e);
            }
            try {
                int expired = lifecycle.expireWaitingLines(now);
                log.info("event=job job=due_work at={} windows_lapsed={} waitlist_expired={}", now, lapsed, expired);
            } catch (RuntimeException e) {
                log.error("event=job_failed job=due_work step=waitlist_expiry at={} cause=\"{}\"", now, e.getMessage(), e);
            }
        });
    }
}
