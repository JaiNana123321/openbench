package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.ForbiddenException;
import edu.cwru.openbench.common.LoanId;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.MemberSummary;
import edu.cwru.openbench.members.Skill;
import edu.cwru.openbench.members.TeamSummary;
import edu.cwru.openbench.notifications.NotificationKind;
import edu.cwru.openbench.notifications.Notifier;
import edu.cwru.openbench.settings.Period;
import edu.cwru.openbench.settings.SettingsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Matched lines (requirements Matched.Shortlist.Build, PE-shortlist, Matched.Approve.Route): a line
 * asks for Persons by skills, or names one Person, over whole days. The system builds a Shortlist
 * and never selects from it; the requesting Manager selects exactly the line's quantity (a named
 * Person counts as selected), and each Candidate's own Manager approves or refuses. A Matched line
 * holds nothing ({@link #hold} is a no-op) and costs nothing (ADR-0004).
 *
 * <p>{@link #evaluate} answers SATISFIABLE once the line's quantity is Approved and every Approved
 * Candidate is still eligible; NEEDS_APPROVAL while approvals are missing and enough Members are
 * eligible; UNSATISFIABLE with {@code SHORTLIST_TOO_SHORT} when fewer eligible Members exist than the
 * quantity, or {@code NOT_A_CANDIDATE} when an Approved or named Person is no longer eligible.
 *
 * <p>{@link #commit} forms one Loan per Approved Candidate once the Request is allocated, after
 * checking each Candidate's eligibility again as a guard ({@code ALLOCATION_CONFLICT}). The Shortlist
 * leaves out Members lent during the window and ranks by person-days lent. A line's own Loans never
 * make its Candidates ineligible for it.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class MatchedAllocator implements Allocator, ApprovalDriven {

    static final String SHORTLIST_TOO_SHORT = "SHORTLIST_TOO_SHORT";
    static final String NOT_A_CANDIDATE = "NOT_A_CANDIDATE";
    static final String ALLOCATION_CONFLICT = "ALLOCATION_CONFLICT";

    private static final Set<ApprovalState> LIVE = EnumSet.of(ApprovalState.OUTSTANDING, ApprovalState.APPROVED);

    private final CandidateRepository candidates;
    private final ApprovalRepository approvals;
    private final LoanRepository loans;
    private final MemberService members;
    private final SettingsService settings;
    private final Notifier notifier;
    private final Clock clock;

    MatchedAllocator(CandidateRepository candidates, ApprovalRepository approvals, LoanRepository loans,
            MemberService members, SettingsService settings, Notifier notifier, Clock clock) {
        this.candidates = candidates;
        this.approvals = approvals;
        this.loans = loans;
        this.members = members;
        this.settings = settings;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Needs no transaction: the registry asks at start-up. */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public AllocationClass allocationClass() {
        return AllocationClass.MATCHED;
    }

    // ---------------------------------------------------------------- Allocator

    @Override
    public Evaluation evaluate(LineSnapshot line) {
        checkClass(line);
        DateRange days = days(line);
        List<Selected> approved = selected(line).stream()
                .filter(selected -> selected.approval().state() == ApprovalState.APPROVED)
                .toList();
        for (Selected selected : approved) {
            MemberId member = new MemberId(selected.candidate().memberId());
            if (!eligible(member, line.teamId(), days, line.lineId())) {
                return Evaluation.unsatisfiable(NOT_A_CANDIDATE, nameOf(member)
                        + " is no longer available every day of " + Windows.describe(days) + ".");
            }
        }
        if (line.namedMember().isPresent()) {
            MemberId named = line.namedMember().get();
            if (approved.isEmpty() && !eligible(named, line.teamId(), days)) {
                return Evaluation.unsatisfiable(NOT_A_CANDIDATE, nameOf(named)
                        + " is not available every day of " + Windows.describe(days) + ", or is lent then.");
            }
            return approved.isEmpty() ? Evaluation.needsApproval() : Evaluation.satisfiable();
        }
        if (approved.size() >= line.quantity()) {
            return Evaluation.satisfiable();
        }
        int eligible = shortlisted(line, days).size();
        if (eligible < line.quantity()) {
            return Evaluation.unsatisfiable(SHORTLIST_TOO_SHORT, (eligible == 1 ? "Only 1 Member is"
                    : (eligible == 0 ? "No Member is" : "Only " + eligible + " Members are"))
                    + " eligible for " + Windows.describe(days) + "; the line asks for " + line.quantity() + ".");
        }
        return Evaluation.needsApproval();
    }

    /** A Matched line holds nothing while approvals are outstanding (design Section 4.5). */
    @Override
    public void hold(LineSnapshot line) {
        checkClass(line);
    }

    /**
     * Forms the line's Loans (SRS Matched.Loan.Form): one {@code loan} per Approved Candidate, lent
     * by the Candidate's Team to the requesting Team for the line's days, with {@code days_lent}
     * their Monday-to-Friday days. Requests re-evaluates the line under the type lock first; the
     * eligibility check here is only a guard, so a failure means a path skipped that step. The
     * {@code loan_one_at_a_time} constraint is the last guard, checked at once by a flush.
     *
     * @throws RuleViolation {@code ALLOCATION_CONFLICT} if an Approved Candidate is no longer eligible
     * @throws IllegalStateException if the line already has Loans, or fewer Approved Candidates than
     *     its quantity
     */
    @Override
    public void commit(LineSnapshot line) {
        checkClass(line);
        if (loans.existsByLineId(line.lineId().value())) {
            throw new IllegalStateException("Line " + line.lineId().value() + " already has its Loans.");
        }
        List<Selected> approved = selected(line).stream()
                .filter(selected -> selected.approval().state() == ApprovalState.APPROVED)
                .toList();
        if (approved.size() < line.quantity()) {
            throw new IllegalStateException("Line " + line.lineId().value() + " has " + approved.size()
                    + " Approved Candidates; it asks for " + line.quantity() + ".");
        }
        DateRange days = days(line);
        for (Selected selected : approved) {
            MemberId member = new MemberId(selected.candidate().memberId());
            if (!eligible(member, line.teamId(), days, line.lineId())) {
                throw new RuleViolation(ALLOCATION_CONFLICT, nameOf(member) + " is no longer available every day of "
                        + Windows.describe(days) + ", or is lent then; Request " + line.requestId().value()
                        + " was not allocated.");
            }
            loans.save(new LoanRow(line.lineId().value(), member.value(),
                    members.summary(member).team().id().value(), line.teamId().value(), days));
            loans.flush();
        }
    }

    @Override
    public Released release(LineSnapshot line, ReleasePart part) {
        checkClass(line);
        throw new UnsupportedOperationException("Ending a Loan early comes with issue #8.");
    }

    /** Cancels every Outstanding or Approved approval of the line; Refused ones stay. Idempotent. */
    @Override
    public void cancel(LineSnapshot line) {
        checkClass(line);
        for (Selected selected : selected(line)) {
            if (LIVE.contains(selected.approval().state())) {
                selected.approval().cancel();
            }
        }
        approvals.flush();
    }

    // ---------------------------------------------------------------- ApprovalDriven

    @Override
    public Shortlist buildShortlist(LineSnapshot line) {
        checkClass(line);
        DateRange days = days(line);
        List<ShortlistRanking.Person> ranked = shortlisted(line, days);
        List<Shortlist.Entry> entries = new ArrayList<>();
        for (ShortlistRanking.Person person : ranked) {
            MemberSummary summary = members.summary(person.id());
            entries.add(new Shortlist.Entry(entries.size() + 1, person.id(), summary.name(), summary.team(),
                    members.skillsOf(person.id()).stream().map(Skill::name).toList(), person.personDaysLent()));
        }
        return new Shortlist(line.lineId(), entries);
    }

    @Override
    public void select(LineSnapshot line, List<MemberId> picked, boolean escalated) {
        checkClass(line);
        if (new HashSet<>(picked).size() != picked.size() || picked.isEmpty()) {
            throw new RuleViolation("LINE_NOT_ATOMIC", "Select each Candidate once, and at least one.");
        }
        List<Selected> selected = selected(line);
        long live = selected.stream().filter(s -> LIVE.contains(s.approval().state())).count();
        if (picked.size() + live != line.quantity()) {
            long missing = line.quantity() - live;
            throw new RuleViolation("LINE_NOT_ATOMIC", "The line asks for " + line.quantity()
                    + (live == 0 ? "" : " and has " + live + " selected") + ": select exactly " + missing
                    + (missing == 1 ? " Candidate." : " Candidates."));
        }
        DateRange days = days(line);
        Set<Long> elsewhere = selectedOnOverlappingLines(line, days);
        Map<MemberId, Integer> positions = new HashMap<>();
        if (line.namedMember().isPresent()) {
            MemberId named = line.namedMember().get();
            if (eligible(named, line.teamId(), days) && !elsewhere.contains(named.value())) {
                positions.put(named, 0);
            }
        } else {
            List<ShortlistRanking.Person> shortlist = shortlisted(line, days);
            for (int i = 0; i < shortlist.size(); i++) {
                positions.put(shortlist.get(i).id(), i + 1);
            }
        }
        Set<Long> alreadySelected = selected.stream().map(s -> s.candidate().memberId()).collect(Collectors.toSet());
        Map<MemberId, MemberSummary> summaries = new LinkedHashMap<>();
        for (MemberId member : picked) {
            Optional<MemberSummary> summary = members.findSummary(member);
            if (summary.isPresent() && summary.get().team().id().equals(line.teamId())) {
                throw new RuleViolation("OWN_TEAM", summary.get().name()
                        + " is a Member of the requesting Team and cannot be lent to it.");
            }
            if (summary.isEmpty() || !positions.containsKey(member) || alreadySelected.contains(member.value())) {
                throw new RuleViolation(NOT_A_CANDIDATE, summary.map(MemberSummary::name).orElse("Member " + member.value())
                        + (alreadySelected.contains(member.value()) ? " was already selected for this line."
                                : elsewhere.contains(member.value())
                                        ? " is already selected on another line of this Request for overlapping days."
                                        : " is not on the line's Shortlist."));
            }
            summaries.put(member, summary.get());
        }
        String requestingTeam = members.team(line.teamId()).name();
        Credits earning = earning(days);
        List<MemberId> planners = escalated ? members.withRole(Role.PLANNER) : List.of();
        for (Map.Entry<MemberId, MemberSummary> entry : summaries.entrySet()) {
            MemberSummary candidate = entry.getValue();
            MemberId approver = members.findManagerOf(candidate.team().id()).orElseThrow(() -> new RuleViolation(
                    NOT_A_CANDIDATE, candidate.team().name() + " has no Manager to approve lending " + candidate.name() + "."));
            CandidateRow row = candidates.save(new CandidateRow(line.lineId().value(), line.requestId().value(),
                    line.teamId().value(), entry.getKey().value(), positions.get(entry.getKey()), days));
            approvals.save(new ApprovalRow(row.id(), approver.value(), escalated));
            String asked = requestingTeam + " asks to borrow " + candidate.name() + " on " + Windows.describe(days)
                    + " (Request " + line.requestId().value() + "); " + candidate.team().name() + " would earn "
                    + earning + " credits.";
            if (escalated) {
                for (MemberId planner : planners) {
                    notifier.notify(planner, NotificationKind.APPROVAL_REQUESTED, asked
                            + " The Objection Window has ended, so the Capacity Planner decides.", "/escalations");
                }
            } else {
                notifier.notify(approver, NotificationKind.APPROVAL_REQUESTED, asked, "/approvals");
            }
            notifier.notify(entry.getKey(), NotificationKind.CANDIDATE_SELECTED, "You were selected as a Candidate for "
                    + requestingTeam + " on " + Windows.describe(days) + "; "
                    + (escalated ? "the Capacity Planner decides." : "your Manager decides."), null);
        }
        approvals.flush();
    }

    @Override
    public Approval decide(LineSnapshot line, ApprovalId id, Decision decision, String reason, Actor actor,
            boolean windowLapsed) {
        checkClass(line);
        ApprovalRow approval = approvals.findById(id.value())
                .orElseThrow(() -> new NotFoundException("Approval " + id.value() + " does not exist."));
        CandidateRow candidate = candidates.findById(approval.candidateId()).orElseThrow();
        if (!candidate.lineId().equals(line.lineId().value())) {
            throw new IllegalArgumentException("Approval " + id.value() + " is not for line " + line.lineId().value() + ".");
        }
        MemberId member = new MemberId(candidate.memberId());
        boolean approver = approval.approverMemberId().equals(actor.memberId().value());
        boolean planner = actor.has(Role.PLANNER);
        boolean lapsed = windowLapsed || approval.escalated();
        if (!approver && !planner) {
            throw new ForbiddenException("Only " + nameOf(new MemberId(approval.approverMemberId()))
                    + ", the Manager of " + nameOf(member) + "'s Team, decides this approval"
                    + (lapsed ? "; since the Objection Window ended, the Capacity Planner does." : "."));
        }
        if (approval.state() != ApprovalState.OUTSTANDING) {
            throw new RuleViolation("ALREADY_DECIDED", "The approval of " + nameOf(member) + " is already "
                    + approval.state().name().toLowerCase() + ".");
        }
        if (lapsed && !planner) {
            throw new RuleViolation("WINDOW_LAPSED", "The Objection Window of Request " + line.requestId().value()
                    + " has ended; the Capacity Planner decides now.");
        }
        if (!lapsed && !approver) {
            throw new ForbiddenException("Only " + nameOf(new MemberId(approval.approverMemberId()))
                    + ", the Manager of " + nameOf(member) + "'s Team, decides this approval until the Objection"
                    + " Window ends.");
        }
        DateRange days = days(line);
        if (decision == Decision.APPROVE && !eligible(member, line.teamId(), days)) {
            throw new RuleViolation(NOT_A_CANDIDATE, nameOf(member) + " is no longer available every day of "
                    + Windows.describe(days) + ", or is lent then.");
        }
        if (lapsed) {
            approval.escalate();
        }
        approval.decide(decision, actor.memberId().value(), clock.instant(), reason);
        approvals.flush();
        return toApproval(approval, candidate);
    }

    @Override
    public List<Approval> refuseIneligible(LineSnapshot line, String reason) {
        checkClass(line);
        DateRange days = days(line);
        List<Approval> refused = new ArrayList<>();
        for (Selected selected : selected(line)) {
            MemberId member = new MemberId(selected.candidate().memberId());
            if (selected.approval().state() == ApprovalState.APPROVED
                    && !eligible(member, line.teamId(), days, line.lineId())) {
                selected.approval().refuseBySystem(clock.instant(), reason);
                refused.add(toApproval(selected.approval(), selected.candidate()));
            }
        }
        approvals.flush();
        return refused;
    }

    @Override
    public boolean candidatesLeft(LineSnapshot line) {
        checkClass(line);
        if (line.namedMember().isPresent()) {
            return false;
        }
        List<Selected> selected = selected(line);
        long live = selected.stream().filter(s -> LIVE.contains(s.approval().state())).count();
        Set<Long> selectedBefore = selected.stream().map(s -> s.candidate().memberId()).collect(Collectors.toSet());
        long left = shortlisted(line, days(line)).stream()
                .filter(person -> !selectedBefore.contains(person.id().value()))
                .count();
        return left >= line.quantity() - live;
    }

    @Override
    public List<Approval> outstanding(RequestId request) {
        return ofRequest(request).stream().filter(approval -> approval.state() == ApprovalState.OUTSTANDING).toList();
    }

    @Override
    public List<Approval> escalate(RequestId request) {
        List<CandidateRow> rows = candidates.findByRequestIdOrderById(request.value());
        Map<Long, CandidateRow> byId = rows.stream().collect(Collectors.toMap(CandidateRow::id, Function.identity()));
        List<Approval> escalated = new ArrayList<>();
        for (ApprovalRow approval : approvals.findByCandidateIdIn(byId.keySet())) {
            if (approval.state() == ApprovalState.OUTSTANDING && !approval.escalated()) {
                approval.escalate();
                escalated.add(toApproval(approval, byId.get(approval.candidateId())));
            }
        }
        approvals.flush();
        return escalated;
    }

    // ---------------------------------------------------------------- reads, for LiveRowQueries

    /** Every approval of the Request's lines, by id. */
    List<Approval> ofRequest(RequestId request) {
        return toApprovals(candidates.findByRequestIdOrderById(request.value()));
    }

    /** Every approval of the line, in the order the Candidates were selected. */
    List<Approval> ofLine(RequestLineId line) {
        return toApprovals(candidates.findByLineIdOrderById(line.value()));
    }

    Optional<Approval> approval(ApprovalId id) {
        return approvals.findById(id.value())
                .map(approval -> toApproval(approval, candidates.findById(approval.candidateId()).orElseThrow()));
    }

    /** The Outstanding approvals addressed to this Manager that are not escalated, by id. */
    List<Approval> awaiting(MemberId approver) {
        return withCandidates(approvals.findByApproverMemberIdAndStateOrderById(approver.value(), ApprovalState.OUTSTANDING)
                .stream().filter(row -> !row.escalated()).toList());
    }

    /** Every escalated Outstanding approval, by id: what the Capacity Planners decide. */
    List<Approval> escalatedOutstanding() {
        return withCandidates(approvals.findByStateAndEscalatedTrueOrderById(ApprovalState.OUTSTANDING));
    }

    /** The escalated approvals addressed to this Manager that someone else decided, by id. */
    List<Approval> decidedOnEscalation(MemberId approver) {
        return withCandidates(approvals.findByApproverMemberIdAndEscalatedTrueAndStateInOrderById(approver.value(),
                        EnumSet.of(ApprovalState.APPROVED, ApprovalState.REFUSED)).stream()
                .filter(row -> row.decidedBy() != null && !row.decidedBy().equals(approver.value()))
                .toList());
    }

    private List<Approval> withCandidates(List<ApprovalRow> rows) {
        Map<Long, CandidateRow> byId = candidates.findAllById(rows.stream().map(ApprovalRow::candidateId).toList())
                .stream().collect(Collectors.toMap(CandidateRow::id, Function.identity()));
        return rows.stream().map(row -> toApproval(row, byId.get(row.candidateId()))).toList();
    }

    /** The Loans the line's commit formed, by id. */
    List<Loan> loansFor(RequestLineId line) {
        return toLoans(loans.findByLineIdOrderById(line.value()));
    }

    /** The Member's Loans, by first day. */
    List<Loan> loansOf(MemberId member) {
        return toLoans(loans.findByMemberIdOrderByStartsOnAscIdAsc(member.value()));
    }

    private List<Loan> toLoans(List<LoanRow> rows) {
        Map<Long, TeamSummary> teams = new HashMap<>();
        Function<Long, TeamSummary> team = id -> teams.computeIfAbsent(id, key -> members.team(new TeamId(key)));
        return rows.stream().map(row -> new Loan(new LoanId(row.id()), new RequestLineId(row.lineId()),
                new MemberId(row.memberId()), nameOf(new MemberId(row.memberId())), team.apply(row.lendingTeamId()),
                team.apply(row.borrowingTeamId()), row.days(), row.daysLent(), row.endedEarly())).toList();
    }

    Set<RequestId> requestsWithUnescalatedOutstanding() {
        return approvals.requestsWithUnescalatedOutstanding().stream().map(RequestId::new)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    // ---------------------------------------------------------------- helpers

    /** The eligible Members, ranked: skilled, available, outside the Team, active, not lent during the window. */
    private List<ShortlistRanking.Person> shortlisted(LineSnapshot line, DateRange days) {
        if (line.namedMember().isPresent()) {
            MemberId named = line.namedMember().get();
            return eligible(named, line.teamId(), days)
                    ? List.of(new ShortlistRanking.Person(named, nameOf(named), true, false, 0))
                    : List.of();
        }
        Set<MemberId> available = new java.util.LinkedHashSet<>(
                members.availableWithSkills(line.skills(), days, line.teamId()));
        Set<Long> elsewhere = selectedOnOverlappingLines(line, days);
        available.removeIf(member -> elsewhere.contains(member.value()));
        if (available.isEmpty()) {
            return List.of();
        }
        List<Long> ids = available.stream().map(MemberId::value).toList();
        Set<Long> lent = new HashSet<>(loans.membersLentDuring(ids, days.from(), days.to()));
        Period period = settings.currentPeriod();
        DateRange periodDays = new DateRange(period.startsOn(), period.endsOn());
        Map<Long, List<DateRange>> loansInPeriod = loans.loansDuring(ids, period.startsOn(), period.endsOn()).stream()
                .collect(Collectors.groupingBy(LoanRow::memberId, Collectors.mapping(LoanRow::days, Collectors.toList())));
        List<ShortlistRanking.Person> people = new ArrayList<>();
        for (MemberId member : available) {
            people.add(new ShortlistRanking.Person(member, nameOf(member), true, lent.contains(member.value()),
                    ShortlistRanking.personDaysLent(loansInPeriod.getOrDefault(member.value(), List.of()), periodDays)));
        }
        return ShortlistRanking.rank(people);
    }

    /**
     * The Members selected, with an Outstanding or Approved approval, on another line of the line's
     * Request whose days overlap these (issue #22): no Candidates for this line, since a Person is in
     * at most one Loan on any day.
     */
    private Set<Long> selectedOnOverlappingLines(LineSnapshot line, DateRange days) {
        return new HashSet<>(candidates.selectedOnOverlappingLines(line.requestId().value(), line.lineId().value(),
                days.from(), days.to(), LIVE));
    }

    /** Active, outside the requesting Team, available every day, and in no Loan overlapping the days. */
    private boolean eligible(MemberId member, TeamId requestingTeam, DateRange days) {
        return eligible(member, requestingTeam, days, null);
    }

    /** {@link #eligible(MemberId, TeamId, DateRange)}, leaving out the Loans of {@code ownLine} when given. */
    private boolean eligible(MemberId member, TeamId requestingTeam, DateRange days, RequestLineId ownLine) {
        Optional<MemberSummary> summary = members.findSummary(member);
        List<Long> lent = ownLine == null
                ? loans.membersLentDuring(List.of(member.value()), days.from(), days.to())
                : loans.membersLentDuringExcept(List.of(member.value()), days.from(), days.to(), ownLine.value());
        return summary.isPresent()
                && summary.get().active()
                && !summary.get().team().id().equals(requestingTeam)
                && members.isAvailable(member, days)
                && lent.isEmpty();
    }

    /** The Earning a Loan over these days would post: the Day Rate per Monday-to-Friday day. */
    private Credits earning(DateRange days) {
        return new Credits(settings.current().dayRate().amount().multiply(BigDecimal.valueOf(days.weekdays())));
    }

    private DateRange days(LineSnapshot line) {
        return Windows.daysOf(line.window(), clock.getZone());
    }

    private String nameOf(MemberId member) {
        return members.findSummary(member).map(MemberSummary::name).orElse("Member " + member.value());
    }

    /** The line's Candidates with their approvals, in the order selected. */
    private List<Selected> selected(LineSnapshot line) {
        List<CandidateRow> rows = candidates.findByLineIdOrderById(line.lineId().value());
        Map<Long, ApprovalRow> byCandidate = approvals.findByCandidateIdIn(rows.stream().map(CandidateRow::id).toList())
                .stream().collect(Collectors.toMap(ApprovalRow::candidateId, Function.identity()));
        return rows.stream().map(row -> new Selected(row, byCandidate.get(row.id()))).toList();
    }

    private List<Approval> toApprovals(Collection<CandidateRow> rows) {
        Map<Long, CandidateRow> byId = rows.stream().collect(Collectors.toMap(CandidateRow::id, Function.identity()));
        return approvals.findByCandidateIdIn(byId.keySet()).stream()
                .sorted(java.util.Comparator.comparing(ApprovalRow::candidateId))
                .map(approval -> toApproval(approval, byId.get(approval.candidateId())))
                .toList();
    }

    private static Approval toApproval(ApprovalRow approval, CandidateRow candidate) {
        return new Approval(new ApprovalId(approval.id()), new RequestLineId(candidate.lineId()),
                new RequestId(candidate.requestId()), new TeamId(candidate.teamId()), new MemberId(candidate.memberId()),
                candidate.position(), new MemberId(approval.approverMemberId()), approval.state(), approval.escalated(),
                approval.decidedBy() == null ? null : new MemberId(approval.decidedBy()), approval.decidedAt(),
                approval.reason());
    }

    private static void checkClass(LineSnapshot line) {
        if (line.allocationClass() != AllocationClass.MATCHED) {
            throw new IllegalArgumentException("Line " + line.lineId().value() + " is not Matched.");
        }
    }

    /** A Candidate of the line and its approval. */
    private record Selected(CandidateRow candidate, ApprovalRow approval) {}
}
