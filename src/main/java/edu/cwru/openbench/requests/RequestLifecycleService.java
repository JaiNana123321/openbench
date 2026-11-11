package edu.cwru.openbench.requests;

import edu.cwru.openbench.allocation.AllocationLock;
import edu.cwru.openbench.allocation.AllocationQueries;
import edu.cwru.openbench.allocation.Allocator;
import edu.cwru.openbench.allocation.AllocatorRegistry;
import edu.cwru.openbench.allocation.Approval;
import edu.cwru.openbench.allocation.ApprovalDriven;
import edu.cwru.openbench.allocation.ApprovalState;
import edu.cwru.openbench.allocation.Decision;
import edu.cwru.openbench.allocation.Evaluation;
import edu.cwru.openbench.allocation.LineSnapshot;
import edu.cwru.openbench.allocation.ReleasePart;
import edu.cwru.openbench.allocation.Released;
import edu.cwru.openbench.allocation.Shortlist;
import edu.cwru.openbench.allocation.WaitingLine;
import edu.cwru.openbench.allocation.Waitlistable;
import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.ledger.ChargeSpec;
import edu.cwru.openbench.ledger.LedgerService;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.MemberSummary;
import edu.cwru.openbench.notifications.NotificationKind;
import edu.cwru.openbench.notifications.Notifier;
import edu.cwru.openbench.settings.PlannerSettings;
import edu.cwru.openbench.settings.SettingsService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Request lifecycle (design Section 4.8, {@code RequestLifecycleService}): submission to
 * Allocated, Pending, or Waiting, withdrawal, the Request detail, and the Matched steps: the
 * Shortlist, the Manager's selection, the lending Managers' decisions, and the Objection Window's
 * lapse. A Request whose Required Person lines need approval is Pending: its Exclusive and Metered
 * lines are Held with their credits until the window ends. Forming Loans, and so allocating a
 * Request with Person lines, comes with issue #6.
 *
 * <p>One transaction per transition, and always this lock order: (0) the Request row, before its
 * lines' type ids are read, so no Draft edit adds a line of a type left unlocked; (1) the lines'
 * Resource Types, ascending ({@link AllocationLock}); (2) the Team's account row, inside
 * {@link LedgerService#holdCredits} and {@link LedgerService#commitCharges}. The early
 * {@link LedgerService#requireAffordable} takes no lock. A deadlock or lock timeout rolls the whole
 * transition back; the controller retries it once ({@code ConcurrencyRetry}).
 *
 * <p>Every change of a Request's or a line's state writes a {@code request_event} in the same
 * transaction. A line's misfit reason is the note of its event, reason code first, such as
 * {@code "QUOTA_EXCEEDED: ..."}; the detail reads it back from there.
 */
@Service
@Transactional(readOnly = true)
public class RequestLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RequestLifecycleService.class);

    /** A misfit note: the reason code, a colon, and the message. */
    private static final Pattern REASON_NOTE = Pattern.compile("^([A-Z_]+): (.*)$", Pattern.DOTALL);

    private final RequestLineRepository lines;
    private final RequestEventRepository events;
    private final RequestAccess access;
    private final LineRules rules;
    private final RequestViews views;
    private final AllocationLock typeLock;
    private final AllocatorRegistry allocators;
    private final AllocationQueries allocation;
    private final LedgerService ledger;
    private final SettingsService settings;
    private final InventoryQueries inventory;
    private final MemberService members;
    private final EntityManager entityManager;
    private final Clock clock;
    private final Notifier notifier;
    private final TransactionTemplate transactions;
    private final Refunds refunds;
    private final PersonLineRules personRules;
    private final RequestRepository requests;

    RequestLifecycleService(RequestLineRepository lines, RequestEventRepository events, RequestAccess access,
            LineRules rules, RequestViews views, AllocationLock typeLock, AllocatorRegistry allocators,
            AllocationQueries allocation, LedgerService ledger, SettingsService settings, InventoryQueries inventory,
            MemberService members, EntityManager entityManager, Clock clock, Notifier notifier,
            TransactionTemplate transactions, Refunds refunds, PersonLineRules personRules, RequestRepository requests) {
        this.personRules = personRules;
        this.requests = requests;
        this.notifier = notifier;
        this.transactions = transactions;
        this.lines = lines;
        this.events = events;
        this.access = access;
        this.rules = rules;
        this.views = views;
        this.typeLock = typeLock;
        this.allocators = allocators;
        this.allocation = allocation;
        this.ledger = ledger;
        this.settings = settings;
        this.inventory = inventory;
        this.members = members;
        this.entityManager = entityManager;
        this.clock = clock;
        this.refunds = refunds;
    }

    // ---------------------------------------------------------------- submit

    /**
     * POST /api/requests/{id}/submit (Draft.Submit.Lock, Lifecycle.Evaluate, Lifecycle.Allocate). In
     * one transaction: refuse early if the Required Quotes exceed the Available Balance; lock the
     * Request; check every line again and lock its Quote; lock the types; evaluate every line. If
     * every Required line fits, each fitting line is held with its credits, then committed and
     * charged, and the Request is Allocated; an Optional line that does not fit, or no longer fits
     * the Balance, is Dropped. Otherwise the Request is Waiting: unfit Required lines join their
     * type's waitlist, unfit Optional lines are Dropped, and nothing is held. Returns the Request in
     * its new state.
     *
     * @throws RuleViolation {@code INSUFFICIENT_BALANCE} with {@code shortfall}, {@code required},
     *     and {@code available}, while the Request stays a Draft; {@code WRONG_STATE} unless it is a
     *     Draft; {@code EMPTY_REQUEST} without lines; any line rule's code (such as
     *     {@code WINDOW_NOT_FUTURE})
     */
    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public Request submit(Actor actor, RequestId id) {
        refuseEarly(actor, id);
        // Forget every entity read without a lock: what follows reads them again after the Request lock.
        entityManager.clear();

        RequestRow request = access.lockForChange(actor, id);
        requireState(request, RequestState.SUBMITTED, "submitted");
        List<RequestLineRow> rows = lines.findByRequestIdOrderById(request.id());
        requireLines(request, rows);
        Instant now = clock.instant();
        PlannerSettings current = settings.current();
        for (RequestLineRow row : rows) {
            if (row.allocationClass() == AllocationClass.MATCHED) {
                checkPersonLine(request, row);
            } else {
                rules.check(new ResourceTypeId(row.typeId()), row.amount(), row.startsAt(), row.endsAt(),
                        row.namedResourceId() == null ? null : new ResourceId(row.namedResourceId()));
            }
            row.lockQuote(views.currentQuote(row, current).map(Credits::amount).orElse(null));
        }
        request.submit(now);
        record(request, null, RequestState.DRAFT.name(), RequestState.SUBMITTED.name(), actor, now, "Quotes locked.");

        typeLock.lockTypes(new TreeSet<>(rows.stream().map(row -> new ResourceTypeId(row.typeId())).toList()));
        List<Evaluated> evaluated = rows.stream().map(row -> {
            LineSnapshot snapshot = snapshot(request, row);
            return new Evaluated(row, snapshot, allocatorOf(row).evaluate(snapshot));
        }).toList();

        if (evaluated.stream().anyMatch(Evaluated::needsApproval)) {
            request.startWindow(ObjectionWindow.endsAt(now, current.objectionWindowWorkingDays(), clock.getZone()));
        }
        settleMatchedLines(request, evaluated, actor, now);
        boolean awaitingApproval = evaluated.stream().anyMatch(line -> line.required() && line.needsApproval());
        boolean matchedLineShort = evaluated.stream()
                .anyMatch(line -> line.required() && line.matched() && !line.fits() && !line.needsApproval());
        boolean everyRequiredLineFits = evaluated.stream().filter(Evaluated::required).allMatch(Evaluated::fits);
        if (everyRequiredLineFits) {
            allocate(request, evaluated, actor, now);
        } else if (awaitingApproval && !matchedLineShort) {
            toPending(request, evaluated, actor, now);
        } else {
            toWaiting(request, evaluated, actor, now);
        }
        routeApprovals(request, evaluated);
        log.info("event=request_submitted request={} team={} state={} actor={}", request.id(), request.teamId(),
                request.state(), actor.memberId().value());
        return views.session().request(request);
    }

    /**
     * The checks before any lock: scope (404), the Team's Manager (403), a Draft with lines, and the
     * Required Quotes within the Available Balance. Under the locks the state and lines are checked
     * again, and the Ledger checks the Balance again under the account lock.
     */
    private void refuseEarly(Actor actor, RequestId id) {
        RequestRow request = access.visible(actor, id);
        RequestAccess.requireTeamsManager(actor, request.teamId());
        requireState(request, RequestState.SUBMITTED, "submitted");
        List<RequestLineRow> rows = lines.findByRequestIdOrderById(request.id());
        requireLines(request, rows);
        Credits required = RequestViews.sum(views.session().lines(request, rows), true);
        ledger.requireAffordable(new TeamId(request.teamId()), required);
    }

    /**
     * Every Required line fits: hold the lines and their credits, Required lines first, each
     * evaluated again just before its Hold so that lines of this Request competing for the same
     * capacity see each other's Holds; then commit every Hold, post the Charges, and the Request is
     * Allocated. If a Required line stops fitting only because of this Request's own earlier Holds,
     * those Holds are cancelled and the Request waits instead.
     */
    private void allocate(RequestRow request, List<Evaluated> evaluated, Actor actor, Instant now) {
        TeamId team = new TeamId(request.teamId());
        List<Evaluated> held = new ArrayList<>();
        List<Evaluated> outcome = new ArrayList<>(evaluated);
        for (Evaluated line : requiredFirst(evaluated)) {
            if (line.matched()) {
                continue;
            }
            Evaluation fit = line.fits() ? allocatorOf(line.row()).evaluate(line.snapshot()) : line.evaluation();
            if (!fit.fits() && line.required()) {
                for (Evaluated hold : held) {
                    allocatorOf(hold.row()).cancel(hold.snapshot());
                    ledger.releaseCredits(team, hold.snapshot().lineId());
                }
                outcome.set(outcome.indexOf(line), line.with(fit));
                toWaiting(request, outcome, actor, now);
                return;
            }
            if (!fit.fits()) {
                drop(request, line.row(), fit.reason(), fit.message(), actor, now);
                continue;
            }
            BigDecimal quote = line.row().lockedQuote();
            if (!line.required() && quote != null && !affordable(team, quote)) {
                drop(request, line.row(), "INSUFFICIENT_BALANCE", "The Optional line's Quote of " + new Credits(quote)
                        + " credits no longer fits the Available Balance.", actor, now);
                continue;
            }
            allocatorOf(line.row()).hold(line.snapshot());
            if (quote != null) {
                ledger.holdCredits(team, line.snapshot().lineId(), new Credits(quote));
            }
            held.add(line);
        }

        List<ChargeSpec> charges = new ArrayList<>();
        for (Evaluated line : held) {
            allocatorOf(line.row()).commit(line.snapshot());
            BigDecimal quote = line.row().lockedQuote();
            if (quote != null) {
                charges.add(new ChargeSpec(team, line.snapshot().lineId(), new Credits(quote)));
            }
        }
        ledger.commitCharges(new RequestId(request.id()), charges);
        for (Evaluated line : held) {
            BigDecimal quote = line.row().lockedQuote();
            changeLine(request, line.row(), RequestLineState.ALLOCATED, actor, now,
                    quote == null ? "No Charge: the type has no pricing settings." : "Charged " + new Credits(quote) + " credits.");
        }
        request.moveTo(RequestState.ALLOCATED);
        record(request, null, RequestState.SUBMITTED.name(), RequestState.ALLOCATED.name(), actor, now, null);
        Credits charged = charges.stream().map(ChargeSpec::amount).reduce(Credits.ZERO, Credits::plus);
        notifyManager(request, "Request " + request.id() + " is Allocated; its Charges total " + charged + " credits.");
    }

    /**
     * A Required line does not fit: unfit Required lines join their type's waitlist, unfit Optional
     * lines are Dropped, the lines that fit wait with their Request, and nothing is held.
     */
    private void toWaiting(RequestRow request, List<Evaluated> evaluated, Actor actor, Instant now) {
        int waiting = 0;
        for (Evaluated line : evaluated) {
            if (line.matched()) {
                waiting += line.row().state() == RequestLineState.WAITING ? 1 : 0;
                continue;
            }
            if (line.fits()) {
                changeLine(request, line.row(), RequestLineState.EVALUATED, actor, now,
                        "Fits now; waits for the Request's other lines.");
            } else if (line.required()) {
                waitlistOf(line.row()).join(line.snapshot());
                changeLine(request, line.row(), RequestLineState.WAITING, actor, now,
                        line.evaluation().reason() + ": " + line.evaluation().message());
                waiting++;
            } else {
                drop(request, line.row(), line.evaluation().reason(), line.evaluation().message(), actor, now);
            }
        }
        request.moveTo(RequestState.WAITING);
        record(request, null, RequestState.SUBMITTED.name(), RequestState.WAITING.name(), actor, now,
                waiting == 1 ? "1 Required line waits for capacity." : waiting + " Required lines wait for capacity.");
        notifyManager(request, "Request " + request.id() + " is Waiting: "
                + (waiting == 1 ? "1 Required line waits for capacity." : waiting + " Required lines wait for capacity."));
    }

    private void drop(RequestRow request, RequestLineRow line, String reason, String message, Actor actor, Instant now) {
        changeLine(request, line, RequestLineState.DROPPED, actor, now, reason + ": " + message);
        notifyManager(request, "An Optional line of Request " + request.id() + " was Dropped: " + message);
    }

    /** Whether the Available Balance, Holds already placed in this transaction included, covers the Quote. */
    private boolean affordable(TeamId team, BigDecimal quote) {
        return quote.compareTo(ledger.account(team).available().amount()) <= 0;
    }

    private static List<Evaluated> requiredFirst(List<Evaluated> evaluated) {
        return evaluated.stream().sorted(Comparator.comparing(line -> !line.required())).toList();
    }

    // ---------------------------------------------------------------- withdraw

    /**
     * POST /api/requests/{id}/withdraw, from Draft or Waiting (Pending in a later increment): every
     * line is Canceled, which takes it off its waitlist and expires any Hold, and held credits are
     * released. Returns the Request, now Withdrawn.
     *
     * @throws RuleViolation {@code WRONG_STATE} from any other state, such as Allocated
     */
    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public Request withdraw(Actor actor, RequestId id) {
        RequestRow request = access.lockForChange(actor, id);
        requireState(request, RequestState.WITHDRAWN, "withdrawn");
        List<RequestLineRow> rows = lines.findByRequestIdOrderById(request.id());
        Instant now = clock.instant();
        String from = request.state().name();
        List<RequestLineRow> held = rows.stream().filter(row -> row.state() == RequestLineState.HELD).toList();
        if (!request.isDraft()) {
            TeamId team = new TeamId(request.teamId());
            typeLock.lockTypes(new TreeSet<>(rows.stream().map(row -> new ResourceTypeId(row.typeId())).toList()));
            for (RequestLineRow row : rows) {
                allocatorOf(row).cancel(snapshot(request, row));
                ledger.releaseCredits(team, new RequestLineId(row.id()));
            }
        }
        for (RequestLineRow row : rows) {
            if (row.state() != RequestLineState.DROPPED) {
                changeLine(request, row, RequestLineState.CANCELED, actor, now, null);
            }
        }
        request.moveTo(RequestState.WITHDRAWN);
        record(request, null, from, RequestState.WITHDRAWN.name(), actor, now, null);
        notifyManager(request, "Request " + request.id() + " was withdrawn.");
        log.info("event=request_withdrawn request={} team={} from={} actor={}", request.id(), request.teamId(), from,
                actor.memberId().value());
        for (RequestLineRow row : held) {
            onCapacityFreed(row.allocationClass(), new ResourceTypeId(row.typeId()), RequestViews.window(row), now);
        }
        return views.session().request(request);
    }

    // ---------------------------------------------------------------- detail

    /**
     * GET /api/requests/{id}: the Request's state; each line with its state, the Resources it is
     * allocated on, its locked Quote, why it waits or was Dropped, and its waitlist position; and the
     * history, oldest first. A Capacity Planner may read any Team's.
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public RequestDetail detail(Actor actor, RequestId id) {
        return detailOf(access.visible(actor, id));
    }

    private RequestDetail detailOf(RequestRow request) {
        List<RequestEventRow> history = events.findByRequestIdOrderByAtAscIdAsc(request.id());
        Map<Long, String> lastNoteByLine = new HashMap<>();
        for (RequestEventRow event : history) {
            if (event.lineId() != null) {
                lastNoteByLine.put(event.lineId(), event.note());
            }
        }
        RequestViews.Session session = views.session();
        List<LineDetail> shown = lines.findByRequestIdOrderById(request.id()).stream()
                .map(row -> lineDetail(session.line(request, row), lastNoteByLine.get(row.id())))
                .toList();
        Map<Long, String> names = new HashMap<>();
        List<RequestEvent> timeline = history.stream().map(event -> new RequestEvent(
                event.at(),
                event.lineId() == null ? null : new RequestLineId(event.lineId()),
                event.fromState(),
                event.toState(),
                event.actorId() == null ? null : new MemberId(event.actorId()),
                event.actorId() == null ? null
                        : names.computeIfAbsent(event.actorId(), member -> members.summary(new MemberId(member)).name()),
                event.note())).toList();
        return new RequestDetail(session.request(request), shown, timeline);
    }

    private LineDetail lineDetail(RequestLine line, String lastNote) {
        String reason = null;
        String message = null;
        boolean explained = line.state() == RequestLineState.WAITING || line.state() == RequestLineState.DROPPED;
        if (explained && lastNote != null) {
            Matcher matcher = REASON_NOTE.matcher(lastNote);
            if (matcher.matches()) {
                reason = matcher.group(1);
                message = matcher.group(2);
            }
        }
        List<LineDetail.AssignedResource> assigned = line.allocationClass() == AllocationClass.EXCLUSIVE
                ? allocation.resourcesOf(line.id()).stream()
                        .map(resource -> new LineDetail.AssignedResource(resource, inventory.resource(resource).name()))
                        .toList()
                : List.of();
        Integer position = line.state() == RequestLineState.WAITING
                ? allocation.waitlistPosition(line.id()).orElse(null)
                : null;
        return new LineDetail(line, assigned, reason, message, position, allocation.allocatedParts(line.id()),
                line.allocationClass() == AllocationClass.MATCHED ? candidatesOf(line.id()) : List.of());
    }

    // ---------------------------------------------------------------- steps

    /** @throws RuleViolation {@code WRONG_STATE} unless a transition leads from the Request's state to {@code next} */
    private static void requireState(RequestRow request, RequestState next, String verb) {
        if (!request.state().canBecome(next)) {
            throw new RuleViolation("WRONG_STATE", "Request " + request.id() + " is "
                    + request.state().name().toLowerCase() + "; it cannot be " + verb + ".");
        }
    }

    private static void requireLines(RequestRow request, List<RequestLineRow> rows) {
        if (rows.isEmpty()) {
            throw new RuleViolation("EMPTY_REQUEST", "Request " + request.id() + " has no lines; add one before submitting.");
        }
    }

    /** The copy of a submitted line that Allocation works on. Demo 1 has no Priority mark and no Bump. */
    private static LineSnapshot snapshot(RequestRow request, RequestLineRow row) {
        boolean metered = row.allocationClass() == AllocationClass.METERED;
        return new LineSnapshot(
                new RequestLineId(row.id()),
                new RequestId(request.id()),
                new TeamId(request.teamId()),
                new ResourceTypeId(row.typeId()),
                row.allocationClass(),
                metered ? 1 : row.amount(),
                metered ? row.amount() : null,
                RequestViews.window(row),
                Optional.ofNullable(row.namedResourceId()).map(ResourceId::new),
                row.required(),
                row.priority(),
                request.submittedAt(),
                false,
                Optional.ofNullable(row.namedMemberId()).map(MemberId::new),
                row.skills().stream().map(SkillId::new).collect(Collectors.toSet()));
    }

    private Allocator allocatorOf(RequestLineRow row) {
        return allocators.forClass(row.allocationClass());
    }

    private Waitlistable waitlistOf(RequestLineRow row) {
        return allocators.capability(row.allocationClass(), Waitlistable.class)
                .orElseThrow(() -> new IllegalStateException(row.allocationClass() + " lines do not wait."));
    }

    private void changeLine(RequestRow request, RequestLineRow line, RequestLineState to, Actor actor, Instant at,
            String note) {
        String from = line.state().name();
        line.setState(to);
        record(request, line.id(), from, to.name(), actor, at, note);
    }

    /** Writes one {@code request_event}; a null actor is the system. */
    private void record(RequestRow request, Long lineId, String from, String to, Actor actor, Instant at, String note) {
        events.save(new RequestEventRow(request.id(), lineId, from, to,
                actor == null ? null : actor.memberId().value(), at, note));
    }

    /** A line with its evaluation under the type locks. */
    private record Evaluated(RequestLineRow row, LineSnapshot snapshot, Evaluation evaluation) {

        boolean required() {
            return row.required();
        }

        boolean fits() {
            return evaluation.fits();
        }

        boolean matched() {
            return row.allocationClass() == AllocationClass.MATCHED;
        }

        boolean needsApproval() {
            return evaluation.outcome() == Evaluation.Outcome.NEEDS_APPROVAL;
        }

        Evaluated with(Evaluation newer) {
            return new Evaluated(row, snapshot, newer);
        }
    }

    // ---------------------------------------------------------------- jobs: waitlist expiry

    /**
     * (Unscoped, for {@code DueWorkJob}) Takes every waiting line whose window started at or before
     * {@code now} off its waitlist (SRS Exclusive.Waitlist.Expire): the line stays Waiting, and so
     * does its Request, until the Manager edits or withdraws it; a line event records the expiry with
     * the system as actor. A line whose window has not started is never touched.
     *
     * <p>Each line is its own transaction, in the lock order of every transition (the Request, then
     * the line's type), and is checked again under the locks. A line that fails is logged with its
     * id and the run moves on; the next run retries it, since its entry is still there. Returns the
     * number of lines taken off.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int expireWaitingLines(Instant now) {
        int expired = 0;
        for (WaitingLine waiting : allocation.waitingStartedBy(now)) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> expireWaitingLine(waiting.lineId(), now)))) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("event=waitlist_expiry_failed line={} cause=\"{}\"", waiting.lineId().value(), e.getMessage(), e);
            }
        }
        return expired;
    }

    /** One line's expiry, in the caller's transaction. False when there was nothing to do. */
    private boolean expireWaitingLine(RequestLineId lineId, Instant now) {
        Long requestId = lines.findRequestIdById(lineId.value())
                .orElseThrow(() -> new IllegalStateException("Line " + lineId.value() + " does not exist."));
        RequestRow request = access.lockForSystem(new RequestId(requestId));
        RequestLineRow row = lines.findById(lineId.value()).orElseThrow();
        if (row.startsAt().isAfter(now)) {
            return false;
        }
        typeLock.lockTypes(new TreeSet<>(List.of(new ResourceTypeId(row.typeId()))));
        if (allocation.waitlistPosition(lineId).isEmpty()) {
            return false;
        }
        if (row.state() != RequestLineState.WAITING) {
            throw new IllegalStateException("Line " + lineId.value() + " is on the waitlist but is "
                    + row.state().name().toLowerCase() + ", not waiting.");
        }
        waitlistOf(row).leave(snapshot(request, row));
        record(request, row.id(), RequestLineState.WAITING.name(), RequestLineState.WAITING.name(), null, now,
                "WINDOW_STARTED: The line's window started before capacity freed, so it left the waitlist."
                        + " Edit or withdraw the Request.");
        log.info("event=waitlist_expired request={} line={} actor=system", request.id(), row.id());
        return true;
    }

    /** A {@code REQUEST_UPDATE} notice to the Manager of the Request's Team, linking to the Request. */
    private void notifyManager(RequestRow request, String text) {
        members.findManagerOf(new TeamId(request.teamId())).ifPresent(manager ->
                notifier.notify(manager, NotificationKind.REQUEST_UPDATE, text, "/requests/" + request.id()));
    }

    // ---------------------------------------------------------------- release and the waitlist fill

    /**
     * POST /api/requests/{id}/lines/{lineId}/release (Release.Line, Release.Refund; design scenario
     * S6). In one transaction: lock the Request, then the line's type; give back the part
     * ({@link Allocator#release}), the rest keeping its instances and prices; post one Refund, the
     * sum over the released pieces of their share of the locked Quote times the Refund Schedule's
     * fraction for the lead time to the start of their occurrence; record the Release, with the part,
     * lead times, fractions, and Refund, in the line's history; and offer the freed capacity to the
     * waitlist ({@link #onCapacityFreed}). The line is Released when nothing of it stays allocated,
     * PartlyReleased otherwise. Returns the line and its Refund.
     *
     * @throws NotFoundException for another Team's Request, or a line not on it
     * @throws RuleViolation {@code NOT_ALLOCATED} unless the line is an allocated Exclusive or Metered
     *     line and the part lies inside its live allocation and has not started; the other codes of
     *     {@link Allocator#release}
     */
    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public ReleaseOutcome releaseLine(Actor actor, RequestId id, RequestLineId lineId, ReleasePart part) {
        RequestRow request = access.lockForChange(actor, id);
        RequestLineRow line = releasableLine(request, lineId);
        Instant now = clock.instant();
        ResourceTypeId type = new ResourceTypeId(line.typeId());
        typeLock.lockTypes(new TreeSet<>(List.of(type)));

        Released released = allocatorOf(line).release(snapshot(request, line), part);
        RefundQuote refund = refunds.quote(line, released, now);
        ledger.refund(new TeamId(request.teamId()), lineId, refund.refund());
        changeLine(request, line, released.nothingLeft() ? RequestLineState.RELEASED : RequestLineState.PARTLY_RELEASED,
                actor, now, refunds.note(refund, describe(part)));
        notifyManager(request, "Line " + line.id() + " of Request " + request.id() + " was "
                + (released.nothingLeft() ? "Released" : "partly Released") + "; Refund " + refund.refund() + " credits.");
        log.info("event=line_released request={} line={} team={} refund={} actor={}", request.id(), line.id(),
                request.teamId(), refund.refund(), actor.memberId().value());

        onCapacityFreed(line.allocationClass(), type, freedWindow(released), now);
        return new ReleaseOutcome(views.session().line(request, line), refund);
    }

    /**
     * GET /api/requests/{id}/lines/{lineId}/release-preview: the Refund a Release of this part would
     * post now, for the Manager to see before confirming. Changes nothing and takes no lock.
     *
     * @throws RuleViolation the codes of {@link #releaseLine}
     */
    @PreAuthorize("hasAuthority('MANAGER')")
    public RefundQuote previewRelease(Actor actor, RequestId id, RequestLineId lineId, ReleasePart part) {
        RequestRow request = access.visible(actor, id);
        RequestAccess.requireTeamsManager(actor, request.teamId());
        RequestLineRow line = releasableLine(request, lineId);
        Released released = allocation.previewRelease(snapshot(request, line), part);
        return refunds.quote(line, released, clock.instant());
    }

    /** The line, if it is on the Request ({@code NotFoundException}) and allocated ({@code NOT_ALLOCATED}). */
    private RequestLineRow releasableLine(RequestRow request, RequestLineId lineId) {
        RequestLineRow line = lines.findById(lineId.value())
                .filter(row -> row.requestId().equals(request.id()))
                .orElseThrow(() -> new NotFoundException(
                        "Line " + lineId.value() + " is not on Request " + request.id() + "."));
        boolean allocated = line.state() == RequestLineState.ALLOCATED
                || line.state() == RequestLineState.PARTLY_RELEASED;
        if (!allocated || line.allocationClass() == AllocationClass.MATCHED) {
            throw new RuleViolation("NOT_ALLOCATED", "Line " + line.id() + " is "
                    + line.state().name().toLowerCase().replace('_', ' ')
                    + "; only an allocated Exclusive or Metered line can be released.");
        }
        return line;
    }

    private String describe(ReleasePart part) {
        return switch (part) {
            case ReleasePart.WholeLine whole -> "the whole line";
            case ReleasePart.Occurrences occurrences -> occurrences.starts().stream().map(refunds::describe)
                    .collect(Collectors.joining(", ", "the occurrences of ", ""));
            case ReleasePart.EarlierEnd earlier -> "the hours from " + refunds.describe(earlier.newEnd());
            case ReleasePart.FewerUnits fewer -> "units, keeping " + fewer.newUnits();
        };
    }

    /** From the earliest released hour to the latest. */
    private static TimeWindow freedWindow(Released released) {
        Instant start = released.pieces().stream().map(piece -> piece.released().start())
                .min(Comparator.naturalOrder()).orElseThrow();
        Instant end = released.pieces().stream().map(piece -> piece.released().end())
                .max(Comparator.naturalOrder()).orElseThrow();
        return new TimeWindow(start, end);
    }

    /**
     * Offers capacity freed on a type to its waitlist (Lifecycle.Waiting.Reevaluate; design
     * {@code onCapacityFreed}): for each waiting line overlapping the freed window, in waitlist order
     * (Bumped, Priority, submission time), its Request is allocated at its locked Quotes if every
     * Required line fits at once and the Team can still afford them; otherwise it keeps its places
     * and the next is tried. A line whose start has passed is skipped. Runs inside the freeing
     * transaction, which holds the freed type; it then locks each waiting Request, its types, and its
     * account, the one path that takes a type after an account, which the one retry covers.
     */
    private void onCapacityFreed(AllocationClass allocationClass, ResourceTypeId type, TimeWindow freed, Instant now) {
        Optional<Waitlistable> waitlist = allocators.capability(allocationClass, Waitlistable.class);
        if (waitlist.isEmpty()) {
            return;
        }
        Set<Long> tried = new HashSet<>();
        for (WaitingLine waiting : waitlist.get().waitingFor(type, freed)) {
            if (!waiting.window().start().isAfter(now)) {
                continue;
            }
            lines.findRequestIdById(waiting.lineId().value())
                    .filter(tried::add)
                    .ifPresent(request -> fillFromWaitlist(request, now));
        }
    }

    /**
     * Allocates a waiting Request if every Required line fits now and its Team can afford the Required
     * Quotes (Lifecycle.Waiting.Reevaluate): all or nothing, never part of the Request.
     */
    private void fillFromWaitlist(Long requestId, Instant now) {
        RequestRow request = access.lockForSystem(new RequestId(requestId));
        if (request.state() != RequestState.WAITING) {
            return;
        }
        List<RequestLineRow> open = lines.findByRequestIdOrderById(request.id()).stream()
                .filter(row -> row.state() == RequestLineState.WAITING || row.state() == RequestLineState.EVALUATED)
                .toList();
        typeLock.lockTypes(new TreeSet<>(open.stream().map(row -> new ResourceTypeId(row.typeId())).toList()));
        List<Evaluated> evaluated = open.stream().map(row -> {
            LineSnapshot snapshot = snapshot(request, row);
            return new Evaluated(row, snapshot, allocatorOf(row).evaluate(snapshot));
        }).toList();
        if (!evaluated.stream().filter(Evaluated::required).allMatch(Evaluated::fits)) {
            return;
        }
        List<RequestLineRow> all = lines.findByRequestIdOrderById(request.id());
        boolean hasRequiredPersonLines = all.stream().anyMatch(row -> row.required()
                && row.allocationClass() == AllocationClass.MATCHED && row.state() != RequestLineState.CANCELED);
        if (hasRequiredPersonLines) {
            // #6 allocates here once every Required Person line is approved and Loans can form. Until
            // then a Request with Required Person lines goes back to Pending while its window runs.
            if (request.windowRunsAt(now) && personLinesOpen(all)) {
                backToPending(request, evaluated, null, now);
            }
            return;
        }
        TeamId team = new TeamId(request.teamId());
        BigDecimal required = evaluated.stream()
                .filter(Evaluated::required)
                .map(line -> line.row().lockedQuote())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (required.compareTo(ledger.lockedAvailable(team).amount()) > 0) {
            log.info("event=waitlist_fill_unaffordable request={} team={} required={}", request.id(), team.value(),
                    required);
            return;
        }
        allocateFromWaitlist(request, evaluated, now);
    }

    /**
     * Allocates a waiting Request whose Required lines fit, by the steps of {@code allocate}: hold
     * each line, Required first, evaluated again so its own lines see each other; drop an Optional
     * line that no longer fits or is unaffordable; then commit, charge the locked Quotes, and take the
     * lines off the waitlist. If a Required line stops fitting only because of this Request's own
     * Holds, they are undone and the Request keeps its places. The system is the actor.
     */
    private void allocateFromWaitlist(RequestRow request, List<Evaluated> evaluated, Instant now) {
        TeamId team = new TeamId(request.teamId());
        List<Evaluated> held = new ArrayList<>();
        for (Evaluated line : requiredFirst(evaluated)) {
            Evaluation fit = line.fits() ? allocatorOf(line.row()).evaluate(line.snapshot()) : line.evaluation();
            if (!fit.fits() && line.required()) {
                for (Evaluated hold : held) {
                    allocatorOf(hold.row()).cancel(hold.snapshot());
                    ledger.releaseCredits(team, hold.snapshot().lineId());
                    if (hold.row().state() == RequestLineState.WAITING) {
                        waitlistOf(hold.row()).join(hold.snapshot());
                    }
                }
                return;
            }
            if (!fit.fits()) {
                drop(request, line.row(), fit.reason(), fit.message(), null, now);
                continue;
            }
            BigDecimal quote = line.row().lockedQuote();
            if (!line.required() && quote != null && !affordable(team, quote)) {
                drop(request, line.row(), "INSUFFICIENT_BALANCE", "The Optional line's Quote of " + new Credits(quote)
                        + " credits no longer fits the Available Balance.", null, now);
                continue;
            }
            allocatorOf(line.row()).hold(line.snapshot());
            if (quote != null) {
                ledger.holdCredits(team, line.snapshot().lineId(), new Credits(quote));
            }
            held.add(line);
        }

        List<ChargeSpec> charges = new ArrayList<>();
        for (Evaluated line : held) {
            allocatorOf(line.row()).commit(line.snapshot());
            if (line.row().state() == RequestLineState.WAITING) {
                waitlistOf(line.row()).leave(line.snapshot());
            }
            BigDecimal quote = line.row().lockedQuote();
            if (quote != null) {
                charges.add(new ChargeSpec(team, line.snapshot().lineId(), new Credits(quote)));
            }
        }
        ledger.commitChargesBySystem(new RequestId(request.id()), charges);
        for (Evaluated line : held) {
            BigDecimal quote = line.row().lockedQuote();
            changeLine(request, line.row(), RequestLineState.ALLOCATED, null, now, "Allocated from the waitlist: "
                    + (quote == null ? "no Charge, the type has no pricing settings." : "charged the locked Quote of "
                            + new Credits(quote) + " credits."));
        }
        request.moveTo(RequestState.ALLOCATED);
        record(request, null, RequestState.WAITING.name(), RequestState.ALLOCATED.name(), null, now,
                "Capacity freed and every Required line fits: allocated from the waitlist.");
        members.findManagerOf(team).ifPresent(manager -> notifier.notify(manager, NotificationKind.WAITLIST_FILLED,
                "Request " + request.id() + " is Allocated from the waitlist: capacity freed and every Required"
                        + " line fits.", "/requests/" + request.id()));
        log.info("event=waitlist_filled request={} team={} lines={}", request.id(), team.value(), held.size());
    }

    // ---------------------------------------------------------------- Person lines: submission

    /** Checks a submitted Person line again, as when it was added ({@link PersonLineRules}). */
    private void checkPersonLine(RequestRow request, RequestLineRow row) {
        personRules.check(new TeamId(request.teamId()), new ResourceTypeId(row.typeId()), row.amount(), row.startsAt(),
                row.endsAt(), null, row.namedMemberId() == null ? null : new MemberId(row.namedMemberId()),
                row.skills().stream().map(SkillId::new).toList());
    }

    /**
     * The Person lines' states at submission: a line that needs approval awaits its Candidates'
     * selection or approval; a Required line with too few eligible Members waits with its reason; an
     * Optional one is Dropped.
     */
    private void settleMatchedLines(RequestRow request, List<Evaluated> evaluated, Actor actor, Instant now) {
        for (Evaluated line : evaluated) {
            if (!line.matched()) {
                continue;
            }
            if (line.needsApproval()) {
                changeLine(request, line.row(), RequestLineState.AWAITING_APPROVAL, actor, now,
                        line.row().namedMemberId() != null ? "Awaiting the approval of the named Person's Manager."
                                : "Awaiting the selection of " + line.row().amount() + " from the Shortlist.");
            } else if (!line.fits() && line.required()) {
                changeLine(request, line.row(), RequestLineState.WAITING, actor, now,
                        line.evaluation().reason() + ": " + line.evaluation().message());
            } else if (!line.fits()) {
                drop(request, line.row(), line.evaluation().reason(), line.evaluation().message(), actor, now);
            }
        }
    }

    /**
     * A Required Person line needs approval: the Request is Pending until its Objection Window ends,
     * with its Exclusive and Metered lines Held ({@link #placeHolds}).
     */
    private void toPending(RequestRow request, List<Evaluated> evaluated, Actor actor, Instant now) {
        placeHolds(request, evaluated, actor, now);
        request.moveTo(RequestState.PENDING);
        record(request, null, RequestState.SUBMITTED.name(), RequestState.PENDING.name(), actor, now,
                "Objection Window until " + refunds.describe(request.windowEndsAt()) + ".");
        notifyManager(request, "Request " + request.id() + " is Pending until " + refunds.describe(request.windowEndsAt())
                + ": its Person lines await selection and approval, and its other lines are Held.");
    }

    /**
     * Holds the Request's Exclusive and Metered lines that fit, with their credits, Required lines
     * first, each evaluated again so the Request's own Holds count (design {@code placeHolds}). A
     * Required line that does not fit waits on its type's waitlist; an Optional one that does not fit,
     * or no longer fits the Balance, is Dropped. Does nothing once the window has ended.
     */
    private void placeHolds(RequestRow request, List<Evaluated> evaluated, Actor actor, Instant now) {
        if (!request.windowRunsAt(now)) {
            return;
        }
        TeamId team = new TeamId(request.teamId());
        for (Evaluated line : requiredFirst(evaluated)) {
            if (line.matched()) {
                continue;
            }
            Evaluation fit = allocatorOf(line.row()).evaluate(line.snapshot());
            if (!fit.fits() && line.required()) {
                waitlistOf(line.row()).join(line.snapshot());
                if (line.row().state() != RequestLineState.WAITING) {
                    changeLine(request, line.row(), RequestLineState.WAITING, actor, now, fit.reason() + ": " + fit.message());
                }
                continue;
            }
            if (!fit.fits()) {
                drop(request, line.row(), fit.reason(), fit.message(), actor, now);
                continue;
            }
            BigDecimal quote = line.row().lockedQuote();
            if (!line.required() && quote != null && !affordable(team, quote)) {
                drop(request, line.row(), "INSUFFICIENT_BALANCE", "The Optional line's Quote of " + new Credits(quote)
                        + " credits no longer fits the Available Balance.", actor, now);
                continue;
            }
            if (line.row().state() == RequestLineState.WAITING) {
                waitlistOf(line.row()).leave(line.snapshot());
            }
            allocatorOf(line.row()).hold(line.snapshot());
            if (quote != null) {
                ledger.holdCredits(team, line.snapshot().lineId(), new Credits(quote));
            }
            changeLine(request, line.row(), RequestLineState.HELD, actor, now, "Held until the Objection Window ends at "
                    + refunds.describe(request.windowEndsAt()) + (quote == null ? "." : "; " + new Credits(quote)
                    + " credits held."));
        }
    }

    /** Selects each named Person whose line needs approval: naming counts as the Manager's selection. */
    private void routeApprovals(RequestRow request, List<Evaluated> evaluated) {
        for (Evaluated line : evaluated) {
            if (line.matched() && line.needsApproval() && line.row().namedMemberId() != null) {
                approvalDriven().select(line.snapshot(), List.of(new MemberId(line.row().namedMemberId())));
            }
        }
    }

    /**
     * Whether every Required Person line is awaiting approval or approved: no Required Person line
     * lacks Candidates for good. Only then may a Waiting Request go back to Pending.
     */
    private static boolean personLinesOpen(List<RequestLineRow> rows) {
        return rows.stream()
                .filter(row -> row.required() && row.allocationClass() == AllocationClass.MATCHED)
                .allMatch(row -> row.state() == RequestLineState.AWAITING_APPROVAL
                        || row.state() == RequestLineState.APPROVED);
    }

    /**
     * Waiting back to Pending while the window runs (design: {@code placeHolds}, window not
     * restarted): the lines that fit are Held again if the Team can afford the Required ones, which is
     * checked under the account lock first so that nothing throws inside another transition. Returns
     * whether the Request is Pending now.
     */
    private boolean backToPending(RequestRow request, List<Evaluated> evaluated, Actor actor, Instant now) {
        TeamId team = new TeamId(request.teamId());
        BigDecimal required = evaluated.stream()
                .filter(line -> !line.matched() && line.required())
                .map(line -> line.row().lockedQuote())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (required.compareTo(ledger.lockedAvailable(team).amount()) > 0) {
            log.info("event=pending_unaffordable request={} team={} required={}", request.id(), team.value(), required);
            return false;
        }
        placeHolds(request, evaluated, actor, now);
        request.moveTo(RequestState.PENDING);
        record(request, null, RequestState.WAITING.name(), RequestState.PENDING.name(), actor, now,
                "Approvals outstanding: Pending again; the Objection Window still ends at "
                        + refunds.describe(request.windowEndsAt()) + ".");
        notifyManager(request, "Request " + request.id() + " is Pending again until "
                + refunds.describe(request.windowEndsAt()) + ".");
        return true;
    }

    // ---------------------------------------------------------------- Person lines: Shortlist and selection

    /**
     * GET /api/requests/{id}/lines/{lineId}/shortlist (Matched.Shortlist.Build): the Person line's
     * Shortlist, computed now. The system selects no one. A Capacity Planner may read any Team's.
     *
     * @throws NotFoundException for a Request out of scope, or a line not on it
     * @throws RuleViolation {@code WRONG_STATE} for a line that is not a Person line, or a Request
     *     not yet submitted
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public Shortlist shortlist(Actor actor, RequestId id, RequestLineId lineId) {
        RequestRow request = access.visible(actor, id);
        RequestLineRow line = personLine(request, lineId);
        return approvalDriven().buildShortlist(snapshot(request, line));
    }

    /**
     * POST /api/requests/{id}/lines/{lineId}/candidates (Matched.Approve.Route): the requesting
     * Manager selects Candidates from the Shortlist; together with those Outstanding or Approved they
     * must be exactly the line's quantity. Each gets an approval request to their own Manager and a
     * notice. A Waiting Request whose window still runs goes back to Pending with its Holds; the
     * window is not restarted. Returns the Request's detail.
     *
     * @throws RuleViolation {@code LINE_NOT_ATOMIC}, {@code NOT_A_CANDIDATE}, {@code OWN_TEAM};
     *     {@code WRONG_STATE} unless the Request is Pending, Waiting, or Allocated and the line awaits
     *     Candidates
     */
    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public RequestDetail selectCandidates(Actor actor, RequestId id, RequestLineId lineId, List<MemberId> picked) {
        RequestRow request = access.lockForChange(actor, id);
        RequestLineRow line = personLine(request, lineId);
        boolean open = line.state() == RequestLineState.AWAITING_APPROVAL || line.state() == RequestLineState.WAITING;
        if (!open || !EnumSet.of(RequestState.PENDING, RequestState.WAITING, RequestState.ALLOCATED)
                .contains(request.state())) {
            throw new RuleViolation("WRONG_STATE", "Line " + line.id() + " is "
                    + line.state().name().toLowerCase().replace('_', ' ') + " on a "
                    + request.state().name().toLowerCase() + " Request; it takes no Candidates now.");
        }
        Instant now = clock.instant();
        List<RequestLineRow> rows = lines.findByRequestIdOrderById(request.id());
        typeLock.lockTypes(new TreeSet<>(rows.stream().map(row -> new ResourceTypeId(row.typeId())).toList()));
        approvalDriven().select(snapshot(request, line), picked);
        String names = picked.stream().map(member -> members.summary(member).name()).collect(Collectors.joining(", "));
        changeLine(request, line, RequestLineState.AWAITING_APPROVAL, actor, now,
                "Selected " + names + "; approval requested.");
        if (request.state() == RequestState.WAITING && request.windowRunsAt(now) && personLinesOpen(rows)) {
            List<Evaluated> capacity = rows.stream()
                    .filter(row -> row.allocationClass() != AllocationClass.MATCHED)
                    .filter(row -> row.state() == RequestLineState.EVALUATED || row.state() == RequestLineState.WAITING)
                    .map(row -> {
                        LineSnapshot snapshot = snapshot(request, row);
                        return new Evaluated(row, snapshot, allocatorOf(row).evaluate(snapshot));
                    })
                    .toList();
            backToPending(request, capacity, actor, now);
        }
        log.info("event=candidates_selected request={} line={} candidates={} actor={}", request.id(), line.id(),
                picked.stream().map(member -> Long.toString(member.value())).collect(Collectors.joining(",")),
                actor.memberId().value());
        return detailOf(request);
    }

    /** The line, if it is a Person line on the Request and the Request was submitted. */
    private RequestLineRow personLine(RequestRow request, RequestLineId lineId) {
        RequestLineRow line = lines.findById(lineId.value())
                .filter(row -> row.requestId().equals(request.id()))
                .orElseThrow(() -> new NotFoundException(
                        "Line " + lineId.value() + " is not on Request " + request.id() + "."));
        if (line.allocationClass() != AllocationClass.MATCHED || request.submittedAt() == null) {
            throw new RuleViolation("WRONG_STATE", "Line " + line.id()
                    + (request.submittedAt() == null ? " is in a Draft; submit the Request first."
                            : " is not a Person line; it has no Shortlist."));
        }
        return line;
    }

    // ---------------------------------------------------------------- approvals

    /**
     * GET /api/approvals: the Outstanding approvals addressed to the caller, oldest first, each with
     * the requesting Team, the days, the Earning, and the end of the Objection Window.
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public List<ApprovalView> approvals(Actor actor) {
        return allocation.approvalsAwaiting(actor.memberId()).stream().map(this::view).toList();
    }

    /**
     * POST /api/approvals/{id}/decision (Matched.Approve.Route): the lending Manager approves or
     * refuses with a reason; the decision is recorded with its actor and time. When the line has its
     * quantity approved it is Approved. A refusal leaves the line awaiting a replacement Candidate,
     * and the requesting Manager is told. Returns the approval as decided.
     *
     * @throws NotFoundException for an unknown approval
     * @throws edu.cwru.openbench.common.ForbiddenException unless the caller is the approver
     * @throws RuleViolation {@code ALREADY_DECIDED}, {@code WINDOW_LAPSED}, {@code NOT_A_CANDIDATE}
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    @Transactional
    public ApprovalView decideApproval(Actor actor, ApprovalId approvalId, Decision decision, String reason) {
        Approval approval = allocation.approval(approvalId)
                .orElseThrow(() -> new NotFoundException("Approval " + approvalId.value() + " does not exist."));
        RequestRow request = access.lockForSystem(approval.requestId());
        List<RequestLineRow> rows = lines.findByRequestIdOrderById(request.id());
        RequestLineRow line = rows.stream().filter(row -> row.id().equals(approval.lineId().value())).findFirst()
                .orElseThrow();
        typeLock.lockTypes(new TreeSet<>(rows.stream().map(row -> new ResourceTypeId(row.typeId())).toList()));
        Instant now = clock.instant();
        boolean lapsed = request.windowEndsAt() != null && !now.isBefore(request.windowEndsAt());
        Approval decided = approvalDriven().decide(snapshot(request, line), approvalId, decision, reason, actor, lapsed);

        String candidate = members.summary(decided.candidate()).name();
        String by = members.summary(actor.memberId()).name();
        String because = reason == null || reason.isBlank() ? "" : ": " + reason;
        if (decision == Decision.APPROVE) {
            long approved = allocation.approvalsOf(approval.lineId()).stream()
                    .filter(each -> each.state() == ApprovalState.APPROVED).count();
            changeLine(request, line, approved >= line.amount() ? RequestLineState.APPROVED : line.state(), actor, now,
                    candidate + " approved by " + by + because);
            boolean everyRequiredPersonLineApproved = rows.stream()
                    .filter(row -> row.required() && row.allocationClass() == AllocationClass.MATCHED)
                    .allMatch(row -> row.state() == RequestLineState.APPROVED);
            if (everyRequiredPersonLineApproved) {
                // Seam for #6: allocate(request, ...) runs here. It re-evaluates every line under the locks,
                // commits the Holds, posts the Charges, forms the Loans, and posts the Earnings. Until Loans
                // can form, the Request stays as it is, with its Holds.
                log.info("event=request_approved request={} next=allocate", request.id());
            }
        } else {
            changeLine(request, line, line.state(), actor, now, candidate + " refused by " + by + because
                    + ". Select a replacement.");
            notifyManager(request, by + " refused " + candidate + " for Request " + request.id() + because
                    + ". Select a replacement from the Shortlist.");
        }
        log.info("event=approval_decided request={} line={} approval={} decision={} actor={}", request.id(), line.id(),
                approvalId.value(), decision, actor.memberId().value());
        return view(decided);
    }

    private ApprovalView view(Approval approval) {
        RequestRow request = requests.findById(approval.requestId().value()).orElseThrow();
        RequestLineRow line = lines.findById(approval.lineId().value()).orElseThrow();
        DateRange days = views.days(line);
        Credits earning = new Credits(settings.current().dayRate().amount().multiply(BigDecimal.valueOf(days.weekdays())));
        return new ApprovalView(approval.id(), approval.requestId(), approval.lineId(), approval.candidate(),
                members.summary(approval.candidate()).name(), members.team(approval.requestingTeam()), days, earning,
                request.windowEndsAt(), approval.state(), approval.escalated(),
                approval.decidedBy() == null ? null : members.summary(approval.decidedBy()).name(),
                approval.decidedAt(), approval.reason());
    }

    /** The Candidates of a Person line with their approvals, for the Request detail. */
    private List<LineDetail.CandidateApproval> candidatesOf(RequestLineId line) {
        return allocation.approvalsOf(line).stream().map(approval -> {
            MemberSummary candidate = members.summary(approval.candidate());
            return new LineDetail.CandidateApproval(approval.id(), approval.candidate(), candidate.name(),
                    candidate.team(), approval.state(), approval.escalated(),
                    approval.decidedBy() == null ? null : members.summary(approval.decidedBy()).name(),
                    approval.decidedAt(), approval.reason());
        }).toList();
    }

    private ApprovalDriven approvalDriven() {
        return allocators.capability(AllocationClass.MATCHED, ApprovalDriven.class)
                .orElseThrow(() -> new IllegalStateException("No allocator decides Matched approvals."));
    }

    // ---------------------------------------------------------------- jobs: the Objection Window's lapse

    /**
     * (Read-only, for {@code DueWorkJob}) The Pending, Waiting, or Allocated Requests whose Objection
     * Window ended at or before {@code now} with an approval still Outstanding and not yet escalated,
     * by id.
     */
    public List<RequestId> lapsedWindows(Instant now) {
        Set<RequestId> unescalated = allocation.requestsWithUnescalatedOutstanding();
        if (unescalated.isEmpty()) {
            return List.of();
        }
        return requests.findIdsWithWindowEndedBy(now,
                        List.of(RequestState.PENDING, RequestState.WAITING, RequestState.ALLOCATED)).stream()
                .map(RequestId::new)
                .filter(unescalated::contains)
                .toList();
    }

    /**
     * (Unscoped, for {@code DueWorkJob}; Lifecycle.Window.Start) The Objection Window lapsed: a
     * Pending Request goes to Waiting, releasing every Hold and its credits and offering the freed
     * capacity to the waitlist; then every Outstanding approval is escalated. A Waiting or Allocated
     * Request is escalated only. Idempotent: a Request whose window runs, or with no unescalated
     * Outstanding approval, is skipped. One transaction; the system is the actor. Escalation notices
     * and the Capacity Planner's list come with issue #7.
     */
    @Transactional
    public void onWindowLapse(RequestId id) {
        RequestRow request = access.lockForSystem(id);
        Instant now = clock.instant();
        if (request.windowEndsAt() == null || request.windowRunsAt(now)) {
            return;
        }
        if (approvalDriven().outstanding(id).stream().allMatch(Approval::escalated)) {
            return;
        }
        List<RequestLineRow> rows = lines.findByRequestIdOrderById(request.id());
        typeLock.lockTypes(new TreeSet<>(rows.stream().map(row -> new ResourceTypeId(row.typeId())).toList()));
        List<RequestLineRow> freed = List.of();
        if (request.state() == RequestState.PENDING) {
            freed = releaseHolds(request, rows, now, "Hold released: the Objection Window ended.");
            request.moveTo(RequestState.WAITING);
            record(request, null, RequestState.PENDING.name(), RequestState.WAITING.name(), null, now,
                    "The Objection Window ended with approvals outstanding; every Hold was released.");
            notifyManager(request, "Request " + request.id() + " is Waiting: its Objection Window ended with approvals"
                    + " outstanding, so its Holds were released.");
        }
        List<Approval> escalated = approvalDriven().escalate(id);
        record(request, null, request.state().name(), request.state().name(), null, now, "ESCALATED: "
                + (escalated.size() == 1 ? "1 approval" : escalated.size() + " approvals")
                + " outstanding when the Objection Window ended went to the Capacity Planner.");
        log.info("event=window_lapsed request={} team={} state={} escalated={} actor=system", request.id(),
                request.teamId(), request.state(), escalated.size());
        for (RequestLineRow row : freed) {
            onCapacityFreed(row.allocationClass(), new ResourceTypeId(row.typeId()), RequestViews.window(row), now);
        }
    }

    /**
     * Cancels the Holds of the Request's Held lines and releases their credits; each line fits and
     * waits for its Request again. Returns the lines whose capacity was freed.
     */
    private List<RequestLineRow> releaseHolds(RequestRow request, List<RequestLineRow> rows, Instant now, String note) {
        TeamId team = new TeamId(request.teamId());
        List<RequestLineRow> held = rows.stream().filter(row -> row.state() == RequestLineState.HELD).toList();
        for (RequestLineRow row : held) {
            allocatorOf(row).cancel(snapshot(request, row));
            ledger.releaseCredits(team, new RequestLineId(row.id()));
            changeLine(request, row, RequestLineState.EVALUATED, null, now, note);
        }
        return held;
    }
}
