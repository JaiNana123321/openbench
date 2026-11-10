package edu.cwru.openbench.jobs;

import edu.cwru.openbench.common.ConcurrencyRetry;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.ledger.LedgerService;
import edu.cwru.openbench.settings.Period;
import edu.cwru.openbench.settings.SettingsService;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The Period rollover (design Scenario S7, SRS Ledger.Grant.Period and PE-jobs: within fifteen
 * minutes of the boundary): daily at 00:05 company time, and once at start-up ({@link #catchUp}),
 * which covers one boundary missed while the server was down. Runs as the system. The rollover
 * itself is {@link LedgerService#rollover}, one transaction that skips a Team already closed, so
 * running it twice for one boundary is harmless. A deadlock or lock timeout is retried once; any
 * failure is logged, and the next start-up's catch-up completes the rollover.
 */
@Component
public class PeriodRolloverJob {

    private static final Logger log = LoggerFactory.getLogger(PeriodRolloverJob.class);

    private final SettingsService settings;
    private final LedgerService ledger;
    private final Clock clock;

    PeriodRolloverJob(SettingsService settings, LedgerService ledger, Clock clock) {
        this.settings = settings;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** The daily run: rolls over only on the day the current Period starts. Tests call it directly. */
    @Scheduled(cron = "0 5 0 * * *", zone = "${openbench.zone}")
    public void run() {
        SystemContext.run(() -> attempt("run", true));
    }

    /**
     * The start-up run: rolls over into the current Period whatever day it is. When the boundary was
     * already rolled over, every Team is skipped and nothing changes.
     */
    public void catchUp() {
        SystemContext.run(() -> attempt("catch_up", false));
    }

    private void attempt(String trigger, boolean onlyOnTheFirstDay) {
        try {
            Period current = settings.currentPeriod();
            if (onlyOnTheFirstDay && !current.startsOn().equals(LocalDate.now(clock))) {
                log.debug("event=job job=period_rollover trigger={} period={} skipped=not_first_day", trigger, current.id().value());
                return;
            }
            if (current.previous().isEmpty()) {
                log.info("event=job job=period_rollover trigger={} period={} skipped=no_previous_period", trigger,
                        current.id().value());
                return;
            }
            PeriodId previous = current.previous().get();
            int closed = ConcurrencyRetry.once(() -> ledger.rollover(previous, current.id()));
            log.info("event=job job=period_rollover trigger={} ending={} starting={} teams_closed={}", trigger,
                    previous.value(), current.id().value(), closed);
        } catch (RuntimeException e) {
            log.error("event=job_failed job=period_rollover trigger={} cause=\"{}\"", trigger, e.getMessage(), e);
        }
    }
}
