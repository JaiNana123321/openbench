package edu.cwru.openbench.requests;

import edu.cwru.openbench.allocation.AllocationLock;
import edu.cwru.openbench.allocation.AllocationQueries;
import edu.cwru.openbench.allocation.Allocator;
import edu.cwru.openbench.allocation.AllocatorRegistry;
import edu.cwru.openbench.allocation.Evaluation;
import edu.cwru.openbench.allocation.LineSnapshot;
import edu.cwru.openbench.allocation.Waitlistable;
import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.ledger.ChargeSpec;
import edu.cwru.openbench.ledger.LedgerService;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.settings.PlannerSettings;
import edu.cwru.openbench.settings.SettingsService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Request lifecycle (design Section 4.8, {@code RequestLifecycleService}): submission to
 * Allocated or Waiting, withdrawal, and the Request detail. Demo 1 has Exclusive and Metered lines
 * only, so a submitted Request never needs approval and never enters Pending.
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

    RequestLifecycleService(RequestLineRepository lines, RequestEventRepository events, RequestAccess access,
            LineRules rules, RequestViews views, AllocationLock typeLock, AllocatorRegistry allocators,
            AllocationQueries allocation, LedgerService ledger, SettingsService settings, InventoryQueries inventory,
            MemberService members, EntityManager entityManager, Clock clock) {
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
            rules.check(new ResourceTypeId(row.typeId()), row.amount(), row.startsAt(), row.endsAt(),
                    row.namedResourceId() == null ? null : new ResourceId(row.namedResourceId()));
            row.lockQuote(views.currentQuote(row, current).map(Credits::amount).orElse(null));
        }
        request.submit(now);
        record(request, null, RequestState.DRAFT.name(), RequestState.SUBMITTED.name(), actor, now, "Quotes locked.");

        typeLock.lockTypes(new TreeSet<>(rows.stream().map(row -> new ResourceTypeId(row.typeId())).toList()));
        List<Evaluated> evaluated = rows.stream().map(row -> {
            LineSnapshot snapshot = snapshot(request, row);
            return new Evaluated(row, snapshot, allocatorOf(row).evaluate(snapshot));
        }).toList();

        boolean everyRequiredLineFits = evaluated.stream().filter(Evaluated::required).allMatch(Evaluated::fits);
        if (everyRequiredLineFits) {
            allocate(request, evaluated, actor, now);
        } else {
            toWaiting(request, evaluated, actor, now);
        }
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
    }

    /**
     * A Required line does not fit: unfit Required lines join their type's waitlist, unfit Optional
     * lines are Dropped, the lines that fit wait with their Request, and nothing is held.
     */
    private void toWaiting(RequestRow request, List<Evaluated> evaluated, Actor actor, Instant now) {
        int waiting = 0;
        for (Evaluated line : evaluated) {
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
    }

    private void drop(RequestRow request, RequestLineRow line, String reason, String message, Actor actor, Instant now) {
        changeLine(request, line, RequestLineState.DROPPED, actor, now, reason + ": " + message);
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
        log.info("event=request_withdrawn request={} team={} from={} actor={}", request.id(), request.teamId(), from,
                actor.memberId().value());
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
        RequestRow request = access.visible(actor, id);
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
        return new LineDetail(line, assigned, reason, message, position);
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
                false);
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

        Evaluated with(Evaluation newer) {
            return new Evaluated(row, snapshot, newer);
        }
    }
}
