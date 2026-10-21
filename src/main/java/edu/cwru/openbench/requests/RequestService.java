package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.inventory.ResourceType;
import edu.cwru.openbench.ledger.LedgerService;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draft editing (design Section 4.8, {@code RequestService}): a Manager creates a Draft for their
 * own Team, adds, edits, splits, and removes lines, marks them Required or Optional, and sees the
 * Draft view with every line's Quote, the sums, the Available Balance, and the shortfall. Demo 1
 * leaves out the Priority mark and Matched lines.
 *
 * <p>Scope ({@link RequestAccess}): a Manager sees only their own Team's Requests and a Capacity
 * Planner every Team's; anything else is not found. Only the Manager of the owning Team edits; a
 * Planner who can see a Request but is not its Manager is forbidden. Edits need the Request in Draft
 * ({@code WRONG_STATE}).
 */
@Service
@Transactional(readOnly = true)
public class RequestService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final RequestRepository requests;
    private final RequestLineRepository lines;
    private final RequestEventRepository events;
    private final RequestAccess access;
    private final LineRules rules;
    private final RequestViews views;
    private final LedgerService ledger;
    private final Clock clock;

    RequestService(RequestRepository requests, RequestLineRepository lines, RequestEventRepository events,
            RequestAccess access, LineRules rules, RequestViews views, LedgerService ledger, Clock clock) {
        this.requests = requests;
        this.lines = lines;
        this.events = events;
        this.access = access;
        this.rules = rules;
        this.views = views;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** POST /api/requests: a new, empty Draft of the caller's Team, recorded in its history. */
    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public Request create(Actor actor) {
        Instant now = clock.instant();
        RequestRow request = requests.save(new RequestRow(actor.teamId().value(), now, actor.memberId().value()));
        events.save(new RequestEventRow(request.id(), null, null, RequestState.DRAFT.name(),
                actor.memberId().value(), now, null));
        return views.session().request(request);
    }

    /** Adds a Required line; its Allocation Class comes from its Resource Type. */
    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public RequestLine addLine(Actor actor, RequestId id, NewLine line) {
        RequestRow request = access.editableDraft(actor, id);
        ResourceType type = rules.check(line.typeId(), line.quantity(), line.start(), line.end(), line.namedResourceId());
        RequestLineRow row = lines.save(new RequestLineRow(request.id(), type.id().value(), type.allocationClass(),
                line.quantity(), line.start(), line.end(), idOrNull(line.namedResourceId()), true,
                clock.instant(), actor.memberId().value()));
        return views.session().line(request, row);
    }

    /** Replaces what a line asks for; its type stays. */
    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public RequestLine editLine(Actor actor, RequestId id, RequestLineId lineId, LineEdit edit) {
        RequestRow request = access.editableDraft(actor, id);
        RequestLineRow row = lineOf(request, lineId);
        rules.check(new ResourceTypeId(row.typeId()), edit.quantity(), edit.start(), edit.end(), edit.namedResourceId());
        row.change(edit.quantity(), edit.start(), edit.end(), idOrNull(edit.namedResourceId()));
        return views.session().line(request, row);
    }

    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public void removeLine(Actor actor, RequestId id, RequestLineId lineId) {
        RequestRow request = access.editableDraft(actor, id);
        lines.delete(lineOf(request, lineId));
    }

    /**
     * Splits a line in two (SRS Draft.Line.Split): {@code quantityForNewLine} of its instances or
     * units move to a new Optional line of the same type and window, and the line keeps the rest.
     * Both parts must keep at least 1 ({@code LINE_NOT_ATOMIC}). Returns the new line.
     */
    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public RequestLine splitLine(Actor actor, RequestId id, RequestLineId lineId, int quantityForNewLine) {
        RequestRow request = access.editableDraft(actor, id);
        RequestLineRow row = lineOf(request, lineId);
        if (quantityForNewLine < 1 || quantityForNewLine >= row.amount()) {
            throw new RuleViolation("LINE_NOT_ATOMIC", row.amount() == 1
                    ? "A line of 1 cannot be split; a line is allocated whole."
                    : "Split off between 1 and " + (row.amount() - 1) + "; a line is allocated whole.");
        }
        RequestLineRow split = lines.save(row.split(quantityForNewLine, clock.instant(), actor.memberId().value()));
        return views.session().line(request, split);
    }

    /** Marks a line Required or Optional (SRS Draft.Line.Required). */
    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public RequestLine setRequired(Actor actor, RequestId id, RequestLineId lineId, boolean required) {
        RequestRow request = access.editableDraft(actor, id);
        RequestLineRow row = lineOf(request, lineId);
        row.setRequired(required);
        return views.session().line(request, row);
    }

    /**
     * GET /api/requests/{id}/draft: the lines with their Quotes (current ones in a Draft, locked ones
     * after submission), the Required and Optional sums, the Team's Available Balance, and the
     * shortfall. A Capacity Planner may read any Team's.
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public DraftView view(Actor actor, RequestId id) {
        RequestRow request = access.visible(actor, id);
        RequestViews.Session session = views.session();
        List<RequestLine> shown = session.lines(request, lines.findByRequestIdOrderById(request.id()));
        Credits required = RequestViews.sum(shown, true);
        Credits optional = RequestViews.sum(shown, false);
        Credits available = ledger.account(new TeamId(request.teamId())).available();
        Credits shortfall = required.compareTo(available) > 0 ? required.minus(available) : Credits.ZERO;
        return new DraftView(session.request(request), shown, required, optional, available, shortfall);
    }

    /**
     * GET /api/requests/lines?ids=...: the lines with these ids that the caller may see, by id, so a
     * Statement can link each Charge to its Request. Ids out of scope or unknown are left out.
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public List<RequestLine> lines(Actor actor, Collection<RequestLineId> ids) {
        List<RequestLineRow> rows = lines.findAllById(ids.stream().map(RequestLineId::value).toList());
        Map<Long, RequestRow> owners = requests.findAllById(rows.stream().map(RequestLineRow::requestId).toList())
                .stream()
                .filter(request -> RequestAccess.mayView(actor, request.teamId()))
                .collect(Collectors.toMap(RequestRow::id, request -> request));
        RequestViews.Session session = views.session();
        return rows.stream()
                .filter(row -> owners.containsKey(row.requestId()))
                .sorted(Comparator.comparing(RequestLineRow::id))
                .map(row -> session.line(owners.get(row.requestId()), row))
                .toList();
    }

    /**
     * GET /api/requests: one page of Requests, newest first, optionally in one state. A Manager sees
     * their own Team's; a Capacity Planner every Team's, or one Team's when {@code team} is given.
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER')")
    public Page<RequestSummary> list(Actor actor, TeamId team, RequestState state, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, NEWEST_FIRST);
        TeamId scoped = team;
        if (!actor.has(Role.PLANNER)) {
            if (team != null && !team.equals(actor.teamId())) {
                throw new NotFoundException("Team " + team.value() + " does not exist.");
            }
            scoped = actor.teamId();
        }
        Page<RequestRow> rows;
        if (scoped == null) {
            rows = state == null ? requests.findAll(pageable) : requests.findByState(state, pageable);
        } else {
            rows = state == null ? requests.findByTeamId(scoped.value(), pageable)
                    : requests.findByTeamIdAndState(scoped.value(), state, pageable);
        }
        Map<Long, List<RequestLineRow>> linesByRequest = lines
                .findByRequestIdInOrderById(rows.getContent().stream().map(RequestRow::id).toList()).stream()
                .collect(Collectors.groupingBy(RequestLineRow::requestId));
        RequestViews.Session session = views.session();
        List<RequestSummary> summaries = rows.getContent().stream().map(row -> {
            List<RequestLine> shown = session.lines(row, linesByRequest.getOrDefault(row.id(), List.of()));
            Request request = session.request(row);
            return new RequestSummary(request.id(), request.team(), request.state(), request.createdAt(),
                    request.submittedAt(), shown.size(), RequestViews.sum(shown, true));
        }).toList();
        return new PageImpl<>(summaries, rows.getPageable(), rows.getTotalElements());
    }

    private RequestLineRow lineOf(RequestRow request, RequestLineId id) {
        return lines.findById(id.value())
                .filter(line -> line.requestId().equals(request.id()))
                .orElseThrow(() -> new NotFoundException("Line " + id.value() + " is not a line of Request "
                        + request.id() + "."));
    }

    private static Long idOrNull(ResourceId id) {
        return id == null ? null : id.value();
    }
}
