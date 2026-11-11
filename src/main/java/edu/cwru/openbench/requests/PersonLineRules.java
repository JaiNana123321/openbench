package edu.cwru.openbench.requests;

import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.ResourceId;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.inventory.AllocationClass;
import edu.cwru.openbench.inventory.InventoryQueries;
import edu.cwru.openbench.inventory.ResourceType;
import edu.cwru.openbench.members.MemberService;
import edu.cwru.openbench.members.MemberSummary;
import edu.cwru.openbench.members.Skill;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The rules of a Person (Matched) line (SRS Draft.Line.Add; design Section 4.8, Table "Requests
 * operations"), checked when it is added or edited and again at submission; {@link LineRules} keeps
 * those of Exclusive and Metered lines. A Person line:
 *
 * <ul>
 * <li>is of a Matched type that exists ({@code TYPE_UNKNOWN}) and is not retired ({@code TYPE_RETIRED});
 * <li>covers whole days: starts and ends at midnight, company time ({@code WINDOW_INVALID}), and
 *     starts after now ({@code WINDOW_NOT_FUTURE});
 * <li>asks for at least 1 Person, and exactly 1 when it names one ({@code QUANTITY_INVALID});
 * <li>names one Person or asks for skills, not both, and names no Resource
 *     ({@code VALIDATION_FAILED}, {@code NAMED_RESOURCE_NOT_OFFERED});
 * <li>names a Member who exists ({@code MEMBER_UNKNOWN}) and is not of the requesting Team
 *     ({@code OWN_TEAM}), and skills on the skill list ({@code SKILL_UNKNOWN}).
 * </ul>
 */
@Component
class PersonLineRules {

    private final InventoryQueries inventory;
    private final MemberService members;
    private final Clock clock;

    PersonLineRules(InventoryQueries inventory, MemberService members, Clock clock) {
        this.inventory = inventory;
        this.members = members;
        this.clock = clock;
    }

    /** Whether the type is Matched; false for an unknown type, which {@link LineRules} refuses. */
    boolean isPersonType(ResourceTypeId typeId) {
        try {
            return inventory.type(typeId).allocationClass() == AllocationClass.MATCHED;
        } catch (NotFoundException e) {
            return false;
        }
    }

    /** Checks a Person line of the Team's Request and returns its skill ids (empty for a named Person). */
    Set<Long> check(TeamId team, ResourceTypeId typeId, int quantity, Instant start, Instant end,
            ResourceId namedResource, MemberId namedMember, Collection<SkillId> skills) {
        ResourceType type = inventory.type(typeId);
        if (type.retired()) {
            throw new RuleViolation("TYPE_RETIRED", "The Resource Type " + type.name() + " is retired.");
        }
        if (!start.isBefore(end) || !isMidnight(start) || !isMidnight(end)) {
            throw new RuleViolation("WINDOW_INVALID",
                    "A Person line covers whole days: from midnight of its first day to midnight after its last.");
        }
        if (!start.isAfter(clock.instant())) {
            throw new RuleViolation("WINDOW_NOT_FUTURE", "The first day must be in the future.");
        }
        if (namedResource != null) {
            throw new RuleViolation("NAMED_RESOURCE_NOT_OFFERED", "A Person line names a Person, not a Resource.");
        }
        List<SkillId> asked = skills == null ? List.of() : List.copyOf(skills);
        if (namedMember != null && !asked.isEmpty()) {
            throw new RuleViolation("VALIDATION_FAILED", "A Person line names one Person or asks for skills, not both.");
        }
        if (quantity < 1) {
            throw new RuleViolation("QUANTITY_INVALID", "A Person line asks for at least 1 Person.");
        }
        if (namedMember != null) {
            MemberSummary named = members.findSummary(namedMember)
                    .orElseThrow(() -> new RuleViolation("MEMBER_UNKNOWN", "Member " + namedMember.value() + " does not exist."));
            if (quantity != 1) {
                throw new RuleViolation("QUANTITY_INVALID", "A line that names " + named.name() + " asks for 1 Person.");
            }
            if (named.team().id().equals(team)) {
                throw new RuleViolation("OWN_TEAM", named.name() + " is a Member of your own Team and cannot be lent to it.");
            }
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        asked.forEach(skill -> ids.add(skill.value()));
        Set<Long> known = new HashSet<>();
        members.skillsById(asked).stream().map(Skill::id).forEach(skill -> known.add(skill.value()));
        Optional<Long> unknown = ids.stream().filter(id -> !known.contains(id)).findFirst();
        if (unknown.isPresent()) {
            throw new RuleViolation("SKILL_UNKNOWN", "Skill " + unknown.get() + " is not on the skill list.");
        }
        return ids;
    }

    private boolean isMidnight(Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone()).toLocalTime().equals(java.time.LocalTime.MIDNIGHT);
    }
}
