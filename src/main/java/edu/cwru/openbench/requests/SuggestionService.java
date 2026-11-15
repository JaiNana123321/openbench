package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.RequestId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.SuggestionId;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.notifications.NotificationKind;
import edu.cwru.openbench.notifications.Notifier;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Suggestions preserve a Member's ask and have no allocation or Ledger effect. */
@Service
@Transactional(readOnly = true)
public class SuggestionService {
    private final SuggestionRepository suggestions;
    private final RequestLineRepository lines;
    private final RequestService requests;
    private final RequestAccess access;
    private final LineRules rules;
    private final PersonLineRules people;
    private final MemberService members;
    private final InventoryQueries inventory;
    private final Notifier notifier;
    private final Clock clock;

    SuggestionService(SuggestionRepository suggestions, RequestLineRepository lines, RequestService requests,
            RequestAccess access, LineRules rules, PersonLineRules people, MemberService members,
            InventoryQueries inventory, Notifier notifier, Clock clock) {
        this.suggestions = suggestions;
        this.lines = lines;
        this.requests = requests;
        this.access = access;
        this.rules = rules;
        this.people = people;
        this.members = members;
        this.inventory = inventory;
        this.notifier = notifier;
        this.clock = clock;
    }

    @PreAuthorize("hasAuthority('MEMBER')")
    @Transactional
    public Suggestion create(Actor actor, NewLine proposal, String note) {
        MemberId manager = members.findManagerOf(actor.teamId()).orElseThrow(() ->
                new RuleViolation("TEAM_NEEDS_MANAGER", "Your Team needs a Manager before you can send a Suggestion."));
        if (people.isPersonType(proposal.typeId())) {
            people.check(actor.teamId(), proposal.typeId(), proposal.quantity(), proposal.start(), proposal.end(),
                    proposal.namedResourceId(), proposal.namedMemberId(), proposal.skills());
        } else {
            rules.check(proposal.typeId(), proposal.quantity(), proposal.start(), proposal.end(), proposal.namedResourceId());
        }
        rules.checkRecurrence(proposal.typeId(), proposal.start(), proposal.end(), proposal.recurrence());
        SuggestionRow row = suggestions.save(new SuggestionRow(actor, manager, proposal,
                inventory.type(proposal.typeId()).allocationClass(), note, clock.instant()));
        Suggestion result = view(row);
        notifier.notify(manager, NotificationKind.SUGGESTION,
                result.memberName() + " sent a Suggestion (" + result.typeName() + ").", "/suggestions");
        return result;
    }

    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public Suggestion acceptIntoNewLine(Actor actor, SuggestionId id, RequestId draft) {
        SuggestionRow row = open(actor, id);
        ownDraft(actor, draft);
        RequestLine line = requests.addLine(actor, draft, row.proposal());
        row.decide(actor, "ACCEPTED", line.id().value(), null, clock.instant());
        return notifyDecision(row);
    }

    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public Suggestion acceptIntoLine(Actor actor, SuggestionId id, RequestLineId lineId) {
        SuggestionRow row = open(actor, id);
        Long requestId = lines.findRequestIdById(lineId.value()).orElseThrow(() -> notFound());
        ownDraft(actor, new RequestId(requestId));
        RequestLineRow line = lines.findById(lineId.value()).orElseThrow(() -> notFound());
        if (line.state() != RequestLineState.DRAFT) {
            throw new RuleViolation("WRONG_STATE", "Only a line in Draft can receive a Suggestion.");
        }
        if (!line.typeId().equals(row.typeId())) {
            throw new RuleViolation("TYPE_MISMATCH", "The Suggestion and target line must have the same Resource Type.");
        }
        row.decide(actor, "ACCEPTED", line.id(), null, clock.instant());
        return notifyDecision(row);
    }

    @PreAuthorize("hasAuthority('MANAGER')")
    @Transactional
    public Suggestion decline(Actor actor, SuggestionId id, String reason) {
        SuggestionRow row = open(actor, id);
        row.decide(actor, "DECLINED", null, reason, clock.instant());
        return notifyDecision(row);
    }

    @PreAuthorize("hasAuthority('MEMBER')")
    public Page<Suggestion> mine(Actor actor, int page, int size) {
        return suggestions.findByMemberIdOrderByCreatedAtDescIdDesc(actor.memberId().value(), PageRequest.of(page, size))
                .map(this::view);
    }

    @PreAuthorize("hasAuthority('MANAGER')")
    public Page<Suggestion> forManager(Actor actor, int page, int size) {
        return suggestions.forManager(actor.teamId().value(), actor.memberId().value(), PageRequest.of(page, size))
                .map(this::view);
    }

    @PreAuthorize("hasAuthority('MEMBER')")
    public Suggestion get(Actor actor, SuggestionId id) {
        SuggestionRow row = suggestions.findById(id.value()).filter(s -> s.memberId().equals(actor.memberId().value())
                || actor.has(Role.MANAGER) && s.teamId().equals(actor.teamId().value())
                && java.util.Objects.equals(actor.memberId().value(), s.managerId())).orElseThrow(() -> notFound());
        return view(row);
    }

    /** Linked asks for the Draft editor, with the Request's usual read scope. */
    @PreAuthorize("hasAuthority('MANAGER')")
    public List<Suggestion> forRequest(Actor actor, RequestId request) {
        RequestRow row = access.visible(actor, request);
        if (!row.teamId().equals(actor.teamId().value())) {
            throw RequestAccess.notFound(request);
        }
        return suggestions.forRequest(request.value()).stream()
                .filter(s -> java.util.Objects.equals(s.managerId(), actor.memberId().value()))
                .map(this::view).toList();
    }

    private SuggestionRow open(Actor actor, SuggestionId id) {
        SuggestionRow row = suggestions.lockForManager(id.value(), actor.teamId().value(), actor.memberId().value())
                .orElseThrow(() -> notFound());
        if (!row.state().equals("OPEN")) {
            throw new RuleViolation("SUGGESTION_CLOSED", "Only Open Suggestions can be accepted or declined.");
        }
        return row;
    }

    private void ownDraft(Actor actor, RequestId draft) {
        // Planner visibility must not reveal another Team's Draft to a Manager deciding a Suggestion.
        RequestRow request = access.visible(actor, draft);
        if (!request.teamId().equals(actor.teamId().value())) {
            throw RequestAccess.notFound(draft);
        }
        access.editableDraft(actor, draft);
    }

    private Suggestion notifyDecision(SuggestionRow row) {
        Suggestion result = view(row);
        String decision = result.state().toLowerCase(java.util.Locale.ROOT);
        notifier.notify(result.memberId(), NotificationKind.SUGGESTION,
                "Your Suggestion (" + result.typeName() + ", " + result.proposal().start().atZone(clock.getZone()).toLocalDate()
                + " to " + result.proposal().end().atZone(clock.getZone()).toLocalDate() + ") was " + decision
                + (result.reason() == null || result.reason().isBlank() ? "." : ": " + result.reason()), "/suggestions");
        return result;
    }

    private Suggestion view(SuggestionRow row) {
        return row.view(members.summary(new MemberId(row.memberId())).name(),
                row.managerId() == null ? null : members.summary(new MemberId(row.managerId())).name(),
                inventory.type(new ResourceTypeId(row.typeId())).name(),
                row.decidedBy() == null ? null : members.summary(new MemberId(row.decidedBy())).name());
    }

    private static NotFoundException notFound() {
        return new NotFoundException("The Suggestion or target does not exist.");
    }
}
