package edu.cwru.openbench.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.ledger.LedgerService;
import edu.cwru.openbench.requests.RequestLifecycleService;
import edu.cwru.openbench.settings.Period;
import edu.cwru.openbench.settings.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The jobs' own decisions, with the services they call replaced: the system context, when to act, and failures. */
class JobsTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final Instant NOW = Instant.parse("2026-12-01T05:05:00Z");

    private final Clock clock = Clock.fixed(NOW, ZONE);

    @AfterEach
    void clearTheSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("DueWorkJob expires waiting lines at the Clock's instant, as the system with every role")
    void runsTheExpiryAsTheSystem() {
        RequestLifecycleService lifecycle = mock(RequestLifecycleService.class);
        AtomicReference<Authentication> during = new AtomicReference<>();
        when(lifecycle.expireWaitingLines(NOW)).thenAnswer(call -> {
            during.set(SecurityContextHolder.getContext().getAuthentication());
            return 2;
        });

        new DueWorkJob(lifecycle, clock).run();

        verify(lifecycle).expireWaitingLines(NOW);
        assertThat(during.get().getName()).isEqualTo(SystemContext.SYSTEM);
        assertThat(during.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(Role.values()).map(Role::name).toList());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("A failed DueWorkJob step is logged, and the run ends normally")
    void logsAFailedExpiry() {
        RequestLifecycleService lifecycle = mock(RequestLifecycleService.class);
        when(lifecycle.expireWaitingLines(any())).thenThrow(new IllegalStateException("database down"));

        new DueWorkJob(lifecycle, clock).run();

        verify(lifecycle).expireWaitingLines(NOW);
    }

    @Test
    @DisplayName("PeriodRolloverJob rolls over on the first day of a Period, and only then")
    void rollsOverOnlyOnTheFirstDay() {
        SettingsService settings = mock(SettingsService.class);
        LedgerService ledger = mock(LedgerService.class);
        Period december = new Period(new PeriodId(2), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31),
                Optional.of(new PeriodId(1)));
        when(settings.currentPeriod()).thenReturn(december);

        new PeriodRolloverJob(settings, ledger, clock).run();
        verify(ledger).rollover(new PeriodId(1), new PeriodId(2));

        LedgerService later = mock(LedgerService.class);
        new PeriodRolloverJob(settings, later, Clock.offset(clock, Duration.ofDays(1))).run();
        verify(later, never()).rollover(any(), any());
    }

    @Test
    @DisplayName("The first Period has nothing to roll over from")
    void skipsTheFirstPeriod() {
        SettingsService settings = mock(SettingsService.class);
        LedgerService ledger = mock(LedgerService.class);
        when(settings.currentPeriod()).thenReturn(
                new Period(new PeriodId(1), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), Optional.empty()));

        new PeriodRolloverJob(settings, ledger, clock).catchUp();

        verify(ledger, never()).rollover(any(), any());
    }

    @Test
    @DisplayName("A failed rollover is logged, and the catch-up ends normally")
    void logsAFailedRollover() {
        SettingsService settings = mock(SettingsService.class);
        LedgerService ledger = mock(LedgerService.class);
        when(settings.currentPeriod()).thenReturn(new Period(new PeriodId(2), LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 31), Optional.of(new PeriodId(1))));
        when(ledger.rollover(any(), any())).thenThrow(new IllegalArgumentException("not adjacent"));

        new PeriodRolloverJob(settings, ledger, clock).catchUp();

        verify(ledger).rollover(new PeriodId(1), new PeriodId(2));
    }

    @Test
    @DisplayName("At start-up the scheduling configuration runs the rollover's catch-up")
    void catchesUpOnStart() {
        PeriodRolloverJob rollover = mock(PeriodRolloverJob.class);

        new JobScheduling(rollover).catchUpOnStart();

        verify(rollover).catchUp();
    }
}
