package edu.cwru.openbench.ledger;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.PeriodId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TransactionId;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.SignedInMember;
import edu.cwru.openbench.members.TeamSummary;
import edu.cwru.openbench.settings.Period;
import edu.cwru.openbench.settings.SettingsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every Team's Account and the append-only Transactions (design Section 4.7). Demo 1 has the
 * Account, held credits, Charges, Grants, and the Statement; Earnings, Refunds, and the rollover
 * come in a later increment.
 *
 * <p>Every change locks the Team's {@code account} row first ({@code SELECT ... FOR UPDATE},
 * inserting it if missing) and keeps it until the caller's transaction ends: the writes join the
 * caller's transaction, after its Resource Type locks, so two Requests of one Team never see the
 * same Available Balance and a failed Charge rolls back every Hold with it.
 *
 * <p>Methods a controller calls carry {@code @PreAuthorize}; the others are for other modules and
 * carry no role check.
 */
@Service
@Transactional(readOnly = true)
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final HeldCreditRepository heldCredits;
    private final PeriodCloseRepository periodCloses;
    private final SettingsService settings;
    private final MemberService members;
    private final Clock clock;

    LedgerService(AccountRepository accounts, TransactionRepository transactions, HeldCreditRepository heldCredits,
            PeriodCloseRepository periodCloses, SettingsService settings, MemberService members, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.heldCredits = heldCredits;
        this.periodCloses = periodCloses;
        this.settings = settings;
        this.members = members;
        this.clock = clock;
    }

    /** (Unscoped) The Team's Account; all zero before its first Transaction. */
    public AccountView account(TeamId team) {
        return accounts.figuresOf(team.value())
                .map(figures -> AccountView.of(team,
                        new Credits(figures.getGranted()), new Credits(figures.getEarned()), new Credits(figures.getHeld())))
                .orElseGet(() -> AccountView.of(team, Credits.ZERO, Credits.ZERO, Credits.ZERO));
    }

    /**
     * GET /api/ledger/account: the caller's own Team's Account, or, for a Capacity Planner, any
     * Team's. Another Team's Account is not found for a Manager.
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public AccountView accountFor(Actor actor, TeamId team) {
        TeamId scoped = team == null ? actor.teamId() : team;
        requireInScope(actor, scoped);
        members.team(scoped);
        return account(scoped);
    }

    /**
     * (Unscoped) The early refusal of a submission: reads the Account without a lock and refuses a
     * sum above the Available Balance. {@link #holdCredits} and {@link #commitCharges} repeat the
     * check under the account lock.
     *
     * @throws RuleViolation {@code INSUFFICIENT_BALANCE}, with the {@code shortfall}
     */
    public void requireAffordable(TeamId team, Credits requiredSum) {
        requireAvailable(requiredSum.amount(), account(team).available().amount());
    }

    /**
     * (Unscoped) Places a line's locked Quote on Hold: held credits grow by the Quote, which must fit
     * the Available Balance. Locks the account row and joins the caller's transaction.
     *
     * @throws RuleViolation {@code INSUFFICIENT_BALANCE}, with the {@code shortfall}
     * @throws IllegalStateException if the line is already on Hold
     */
    @Transactional
    public void holdCredits(TeamId team, RequestLineId line, Credits quote) {
        Account account = lock(team);
        if (heldCredits.existsById(line.value())) {
            throw new IllegalStateException("Line " + line.value() + " is already on Hold.");
        }
        requireAvailable(quote.amount(), account.available());
        account.hold(quote.amount());
        heldCredits.save(new HeldCredit(line.value(), team.value(), quote.amount()));
    }

    /**
     * (Unscoped) Releases a line's Hold: held credits shrink by its held amount. Releasing a line
     * that is not on Hold changes nothing.
     */
    @Transactional
    public void releaseCredits(TeamId team, RequestLineId line) {
        Account account = lock(team);
        heldCredits.findById(line.value()).ifPresent(held -> {
            requireSameTeam(held, team, line);
            account.release(held.amount());
            heldCredits.delete(held);
        });
    }

    /**
     * (Unscoped) Posts a Charge for each line of an allocated Request, equal to its locked Quote,
     * drawn from granted credits first and then from earned ones, with the split recorded. A line on
     * Hold has its held credits released in the same step; a line never held (a Waiting Request
     * allocated without a Hold) is charged the same way. The Charges together must fit the Available
     * Balance once the lines' own held credits are released, so no Charge takes the Balance below
     * zero. Locks each Team's account row (in ascending id) and joins the caller's transaction.
     *
     * <p>The Transactions name the signed-in Member as their actor, or the system in a job.
     *
     * @throws RuleViolation {@code INSUFFICIENT_BALANCE}, with the {@code shortfall}
     */
    @Transactional
    public void commitCharges(RequestId request, List<ChargeSpec> charges) {
        Map<TeamId, List<ChargeSpec>> byTeam = charges.stream().collect(Collectors.groupingBy(
                ChargeSpec::team, () -> new TreeMap<>((a, b) -> Long.compare(a.value(), b.value())), Collectors.toList()));
        PeriodId period = byTeam.isEmpty() ? null : settings.currentPeriod().id();
        Long actor = signedInMemberId();
        byTeam.forEach((team, lines) -> {
            Account account = lock(team);
            BigDecimal total = BigDecimal.ZERO;
            for (ChargeSpec charge : lines) {
                heldCredits.findById(charge.line().value()).ifPresent(held -> {
                    requireSameTeam(held, team, charge.line());
                    account.release(held.amount());
                    heldCredits.delete(held);
                });
                total = total.add(charge.amount().amount());
            }
            requireAvailable(total, account.available());
            for (ChargeSpec charge : lines) {
                BigDecimal amount = charge.amount().amount();
                BigDecimal fromGranted = account.draw(amount);
                Transaction posted = transactions.save(Transaction.charge(team.value(), fromGranted,
                        amount.subtract(fromGranted), period.value(), charge.line().value(), actor, clock.instant()));
                log.info("event=transaction kind=CHARGE id={} team={} amount={} request={} line={} actor={}",
                        posted.id(), team.value(), amount, request.value(), charge.line().value(), actorText(actor));
            }
        });
    }

    /**
     * (Unscoped) Posts a Grant into a Period: granted credits grow by the amount. {@code reason} is
     * kept for a mid-Period Grant and may be null for a planned one; {@code actor} is null for the
     * rollover. Locks the account row and joins the caller's transaction.
     *
     * @throws RuleViolation {@code INVALID_AMOUNT} unless the amount is positive
     */
    @Transactional
    public void postGrant(TeamId team, PeriodId period, Credits amount, String reason, Actor actor) {
        if (amount.amount().signum() <= 0) {
            throw new RuleViolation("INVALID_AMOUNT", "A Grant must be a positive number of credits.");
        }
        settings.period(period);
        Account account = lock(team);
        account.grant(amount.amount());
        Long actorId = actor == null ? null : actor.memberId().value();
        Transaction posted = transactions.save(Transaction.grant(team.value(), amount.amount(), period.value(), reason,
                actorId, clock.instant()));
        log.info("event=transaction kind=GRANT id={} team={} amount={} period={} actor={}",
                posted.id(), team.value(), amount, period.value(), actorText(actorId));
    }

    /**
     * POST /api/ledger/grants: the Capacity Planner's mid-Period Grant to a Team, with a reason,
     * into the current Period. Returns the Team's Account after it.
     *
     * @throws RuleViolation {@code REASON_REQUIRED} without a reason; {@code INVALID_AMOUNT} unless
     *     the amount is positive
     */
    @PreAuthorize("hasAuthority('PLANNER')")
    @Transactional
    public AccountView grantMidPeriod(Actor actor, TeamId team, Credits amount, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new RuleViolation("REASON_REQUIRED", "Give a reason for a mid-Period Grant.");
        }
        members.team(team);
        postGrant(team, settings.currentPeriod().id(), amount, reason.strip(), actor);
        return account(team);
    }

    /**
     * GET /api/ledger/statements/{teamId}/{periodId}: the Team's Statement for the Period, for the
     * Team's own Manager or a Capacity Planner. Another Team's Statement is not found for a Manager.
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public Statement statement(Actor actor, TeamId team, PeriodId period) {
        requireInScope(actor, team);
        TeamSummary summary = members.team(team);
        List<Period> periods = settings.periods();
        Period target = periods.stream().filter(p -> p.id().equals(period)).findFirst()
                .orElseThrow(() -> new NotFoundException("Period " + period.value() + " does not exist."));
        Set<Long> earlier = periods.stream()
                .filter(p -> p.startsOn().isBefore(target.startsOn()))
                .map(p -> p.id().value())
                .collect(Collectors.toSet());

        BigDecimal openingGranted = BigDecimal.ZERO;
        BigDecimal openingEarned = BigDecimal.ZERO;
        BigDecimal closingGranted = BigDecimal.ZERO;
        BigDecimal closingEarned = BigDecimal.ZERO;
        Map<TransactionKind, BigDecimal> totals = new EnumMap<>(TransactionKind.class);
        List<Transaction> inPeriod = new ArrayList<>();
        for (Transaction transaction : transactions.findByTeamIdOrderByPostedAtAscIdAsc(team.value())) {
            if (earlier.contains(transaction.periodId())) {
                openingGranted = openingGranted.add(transaction.signedGranted());
                openingEarned = openingEarned.add(transaction.signedEarned());
            } else if (transaction.periodId().equals(period.value())) {
                closingGranted = closingGranted.add(transaction.signedGranted());
                closingEarned = closingEarned.add(transaction.signedEarned());
                totals.merge(transaction.kind(), transaction.amount(), BigDecimal::add);
                inPeriod.add(transaction);
            }
        }
        BigDecimal expired = BigDecimal.ZERO;
        for (PeriodClose close : periodCloses.findByTeamId(team.value())) {
            if (earlier.contains(close.periodId())) {
                openingGranted = openingGranted.subtract(close.expired());
            } else if (close.periodId().equals(period.value())) {
                expired = close.expired();
            }
        }
        closingGranted = openingGranted.add(closingGranted).subtract(expired);
        closingEarned = openingEarned.add(closingEarned);

        Map<Long, String> names = new HashMap<>();
        List<TransactionView> lines = inPeriod.stream().map(transaction -> view(transaction, names)).toList();
        return new Statement(summary, target,
                new Statement.Figures(new Credits(openingGranted), new Credits(openingEarned)),
                new Statement.Totals(total(totals, TransactionKind.GRANT), total(totals, TransactionKind.CHARGE),
                        total(totals, TransactionKind.EARNING), total(totals, TransactionKind.REFUND), new Credits(expired)),
                new Statement.Figures(new Credits(closingGranted), new Credits(closingEarned)),
                account(team),
                lines);
    }

    private TransactionView view(Transaction transaction, Map<Long, String> names) {
        Long actor = transaction.actorId();
        String actorName = actor == null ? null
                : names.computeIfAbsent(actor, id -> members.summary(new MemberId(id)).name());
        return new TransactionView(
                new TransactionId(transaction.id()),
                transaction.kind(),
                new Credits(transaction.amount()),
                new Credits(transaction.grantedPart()),
                new Credits(transaction.earnedPart()),
                transaction.postedAt(),
                transaction.lineId() == null ? null : new RequestLineId(transaction.lineId()),
                actor == null ? null : new MemberId(actor),
                actorName,
                transaction.reason());
    }

    private static Credits total(Map<TransactionKind, BigDecimal> totals, TransactionKind kind) {
        return new Credits(totals.getOrDefault(kind, BigDecimal.ZERO));
    }

    /** Locks the Team's account row, creating it with zero totals first if it does not exist. */
    private Account lock(TeamId team) {
        return accounts.lockByTeamId(team.value()).orElseGet(() -> {
            accounts.createIfMissing(team.value());
            return accounts.lockByTeamId(team.value()).orElseThrow();
        });
    }

    /** A Manager sees their own Team; a Capacity Planner sees every Team. Anything else is not found. */
    private static void requireInScope(Actor actor, TeamId team) {
        boolean ownTeam = actor.has(Role.MANAGER) && actor.teamId().equals(team);
        if (!ownTeam && !actor.has(Role.PLANNER)) {
            throw new NotFoundException("Team " + team.value() + " does not exist.");
        }
    }

    private static void requireAvailable(BigDecimal required, BigDecimal available) {
        if (required.compareTo(available) > 0) {
            Credits needed = new Credits(required);
            Credits have = new Credits(available);
            throw new RuleViolation("INSUFFICIENT_BALANCE",
                    "This needs " + needed + " credits; the Available Balance is " + have + ".",
                    Map.of("shortfall", needed.minus(have), "required", needed, "available", have));
        }
    }

    private static void requireSameTeam(HeldCredit held, TeamId team, RequestLineId line) {
        if (!held.teamId().equals(team.value())) {
            throw new IllegalArgumentException("Line " + line.value() + " is held for another Team.");
        }
    }

    /** The Member whose call this is, or null for the system (a job). */
    private static Long signedInMemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SignedInMember member) {
            return member.actor().memberId().value();
        }
        return null;
    }

    private static String actorText(Long actorId) {
        return actorId == null ? "system" : actorId.toString();
    }
}
