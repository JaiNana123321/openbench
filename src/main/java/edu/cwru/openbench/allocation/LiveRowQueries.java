package edu.cwru.openbench.allocation;

import edu.cwru.openbench.common.ApprovalId;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.TeamSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The read side of Allocation; unscoped, for other modules, which check roles themselves. */
@Component
@Transactional(readOnly = true)
class LiveRowQueries implements AllocationQueries {

    private final WaitlistEntryRepository waitlist;
    private final ExclusiveOccurrenceRepository occurrences;
    private final HourlySums sums;
    private final MeteredAllocationRepository metered;
    private final ExclusiveAllocator exclusiveAllocator;
    private final MeteredAllocator meteredAllocator;
    private final MatchedAllocator matchedAllocator;
    private final MemberService members;

    LiveRowQueries(WaitlistEntryRepository waitlist, ExclusiveOccurrenceRepository occurrences, HourlySums sums,
            MeteredAllocationRepository metered, ExclusiveAllocator exclusiveAllocator,
            MeteredAllocator meteredAllocator, MatchedAllocator matchedAllocator, MemberService members) {
        this.members = members;
        this.matchedAllocator = matchedAllocator;
        this.waitlist = waitlist;
        this.occurrences = occurrences;
        this.sums = sums;
        this.metered = metered;
        this.exclusiveAllocator = exclusiveAllocator;
        this.meteredAllocator = meteredAllocator;
    }

    @Override
    public Optional<Integer> waitlistPosition(RequestLineId line) {
        return Optional.ofNullable(waitlist.positionOf(line.value()));
    }

    @Override
    public List<ResourceId> resourcesOf(RequestLineId line) {
        return occurrences.resourcesOfLine(line.value(), RowState.LIVE).stream().map(ResourceId::new).toList();
    }

    @Override
    public int peakUnitsInUse(ResourceTypeId type, Optional<ResourceId> instance, Instant from) {
        return HourlyLoad.peak(sums.hoursFrom(type, instance, from));
    }

    @Override
    public List<WaitingLine> waitingStartedBy(Instant now) {
        return waitlist.findByStartsAtLessThanEqualOrderById(now).stream().map(WaitlistEntryRow::toWaitingLine).toList();
    }

    @Override
    public Released previewRelease(LineSnapshot line, ReleasePart part) {
        return switch (line.allocationClass()) {
            case EXCLUSIVE -> exclusiveAllocator.preview(line, part);
            case METERED -> meteredAllocator.preview(line, part);
            case MATCHED -> throw new IllegalArgumentException("A Matched line is released by ending its Loan early.");
        };
    }

    @Override
    public List<AllocatedPart> allocatedParts(RequestLineId line) {
        Map<TimeWindow, Integer> parts = new TreeMap<>(Comparator.comparing(TimeWindow::start)
                .thenComparing(TimeWindow::end));
        occurrences.findByLineIdAndStateIn(line.value(), Set.of(RowState.ALLOCATED))
                .forEach(row -> parts.merge(row.window(), 1, Integer::sum));
        metered.findByLineIdAndStateIn(line.value(), Set.of(RowState.ALLOCATED))
                .forEach(row -> parts.merge(row.window(), row.units(), Integer::sum));
        return parts.entrySet().stream().map(part -> new AllocatedPart(part.getKey(), part.getValue())).toList();
    }

    @Override
    public List<Approval> approvalsAwaiting(MemberId approver) {
        return matchedAllocator.awaiting(approver);
    }

    @Override
    public Optional<Approval> approval(ApprovalId id) {
        return matchedAllocator.approval(id);
    }

    @Override
    public List<Approval> approvalsOf(RequestLineId line) {
        return matchedAllocator.ofLine(line);
    }

    @Override
    public List<Approval> approvalsOfRequest(RequestId request) {
        return matchedAllocator.ofRequest(request);
    }

    @Override
    public Set<RequestId> requestsWithUnescalatedOutstanding() {
        return matchedAllocator.requestsWithUnescalatedOutstanding();
    }

    @Override
    public List<Loan> loansFor(RequestLineId line) {
        return matchedAllocator.loansFor(line);
    }

    @Override
    public List<Loan> loansOf(MemberId member) {
        return matchedAllocator.loansOf(member);
    }

    @Override
    public List<Holding> holders(ResourceId resource, TimeWindow window) {
        List<HolderTimeline.Piece> live = occurrences
                .onResource(resource.value(), RowState.LIVE, window.start(), window.end()).stream()
                .map(row -> new HolderTimeline.Piece(row.window(), row.state(), row.teamId(), row.requestId(),
                        row.priority()))
                .toList();
        Map<Long, TeamSummary> teams = new HashMap<>();
        return HolderTimeline.of(window, live).stream()
                .map(part -> part.state() == HoldingState.FREE
                        ? Holding.free(part.window())
                        : new Holding(part.window(), part.state(),
                                teams.computeIfAbsent(part.teamId(), id -> members.team(new TeamId(id))),
                                new RequestId(part.requestId()), part.priority()))
                .toList();
    }

    @Override
    public List<Approval> escalatedOutstanding() {
        return matchedAllocator.escalatedOutstanding();
    }

    @Override
    public List<Approval> decidedOnEscalation(MemberId approver) {
        return matchedAllocator.decidedOnEscalation(approver);
    }

    @Override
    public BigDecimal peakFill(ResourceTypeId type, List<TimeWindow> occurrences) {
        return sums.peakFill(type, occurrences);
    }
}
