package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.RequestLineId;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.SuggestionId;
import edu.cwru.openbench.common.WeeklyRecurrence;
import edu.cwru.openbench.inventory.AllocationClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;

@Entity
@Table(name = "suggestion")
class SuggestionRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long memberId;
    private Long teamId;
    private Long managerId;
    private Long typeId;
    private Integer quantity;
    private Integer units;
    private Instant startsAt;
    private Instant endsAt;
    private Long namedResourceId;
    private Long namedMemberId;
    private Integer recurrence;
    @Column(columnDefinition = "bigint[]")
    private Long[] skills;
    private String note;
    private String state;
    private Long acceptedLineId;
    private String reason;
    private Long decidedBy;
    private Instant decidedAt;
    private Instant createdAt;
    private Long createdBy;

    protected SuggestionRow() {}

    SuggestionRow(Actor actor, MemberId manager, NewLine proposal, AllocationClass allocationClass,
            String note, Instant now) {
        memberId = actor.memberId().value();
        createdBy = memberId;
        teamId = actor.teamId().value();
        managerId = manager.value();
        typeId = proposal.typeId().value();
        quantity = allocationClass == AllocationClass.METERED ? 1 : proposal.quantity();
        units = allocationClass == AllocationClass.METERED ? proposal.quantity() : null;
        startsAt = proposal.start();
        endsAt = proposal.end();
        namedResourceId = proposal.namedResourceId() == null ? null : proposal.namedResourceId().value();
        namedMemberId = proposal.namedMemberId() == null ? null : proposal.namedMemberId().value();
        recurrence = proposal.recurrence() == null ? null : proposal.recurrence().occurrences();
        skills = proposal.skills() == null ? null : proposal.skills().stream().map(SkillId::value).toArray(Long[]::new);
        this.note = note;
        state = "OPEN";
        createdAt = now;
    }

    Long id() { return id; }
    Long memberId() { return memberId; }
    Long teamId() { return teamId; }
    Long managerId() { return managerId; }
    Long typeId() { return typeId; }
    Long decidedBy() { return decidedBy; }
    String state() { return state; }

    NewLine proposal() {
        return new NewLine(new ResourceTypeId(typeId), units == null ? quantity : units, startsAt, endsAt,
                namedResourceId == null ? null : new ResourceId(namedResourceId),
                recurrence == null ? null : new WeeklyRecurrence(recurrence),
                namedMemberId == null ? null : new MemberId(namedMemberId),
                skills == null ? null : Arrays.stream(skills).map(SkillId::new).toList(), null);
    }

    void decide(Actor actor, String state, Long line, String reason, Instant now) {
        if (!this.state.equals("OPEN")) {
            throw new RuleViolation("SUGGESTION_CLOSED", "Only Open Suggestions can be accepted or declined.");
        }
        this.state = state;
        acceptedLineId = line;
        this.reason = reason;
        decidedBy = actor.memberId().value();
        decidedAt = now;
    }

    Suggestion view(String member, String manager, String type, String decider) {
        return new Suggestion(new SuggestionId(id), new MemberId(memberId), member,
                managerId == null ? null : new MemberId(managerId), manager, type, proposal(), note, state,
                acceptedLineId == null ? null : new RequestLineId(acceptedLineId),
                decidedBy == null ? null : new MemberId(decidedBy), decider, decidedAt, reason, createdAt);
    }
}
