package edu.cwru.openbench.members;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.ForbiddenException;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.NotFoundException;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.RuleViolation;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.TeamId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who the people are (design Section 4.1): the signed-in Member, the Teams, profiles with their
 * skills and availability, the skill list, and the unscoped lookups other modules use for their own
 * rules, among them the two questions matching asks: {@link #isAvailable} and
 * {@link #availableWithSkills}.
 *
 * <p>Methods a controller calls carry {@code @PreAuthorize}; methods only other modules call carry
 * no role check, because no controller reaches them. A profile outside the caller's scope is
 * reported as not found, never as forbidden.
 */
@Service
@Transactional(readOnly = true)
public class MemberService {

    /** Passed to the skill query in place of an empty list, which no skill has as its id. */
    private static final List<Long> NO_SKILL = List.of(-1L);

    private static final Comparator<Skill> BY_NAME = Comparator.comparing(skill -> skill.name().toLowerCase());

    private final MemberRepository members;
    private final TeamRepository teams;
    private final MemberRoleRepository roles;
    private final SkillRepository skills;
    private final MemberSkillRepository memberSkills;
    private final AvailabilityRangeRepository availability;
    private final Clock clock;

    MemberService(MemberRepository members, TeamRepository teams, MemberRoleRepository roles, SkillRepository skills,
            MemberSkillRepository memberSkills, AvailabilityRangeRepository availability, Clock clock) {
        this.members = members;
        this.teams = teams;
        this.roles = roles;
        this.skills = skills;
        this.memberSkills = memberSkills;
        this.availability = availability;
        this.clock = clock;
    }

    /** The signed-in Member: GET /api/me and the body of a successful sign-in. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public MemberSummary me(Actor actor) {
        return summary(actor.memberId());
    }

    /** The active Teams, by name. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public List<TeamSummary> teams() {
        return teams.findByRetiredAtIsNullOrderByName().stream().map(Team::toSummary).toList();
    }

    /** (Unscoped) A Member's name, Team, active flag, and roles, for another module's rule. */
    public MemberSummary summary(MemberId id) {
        Member member = member(id);
        return summaryOf(member, teams.findById(member.teamId()).orElseThrow());
    }

    /** The Team's current Manager. */
    public MemberId managerOf(TeamId team) {
        Team found = team(team.value());
        if (found.managerMemberId() == null) {
            throw new NotFoundException("Team " + team.value() + " has no Manager.");
        }
        return new MemberId(found.managerMemberId());
    }

    /** The active Members holding a role, by id; every active Member for {@link Role#MEMBER}. */
    public List<MemberId> withRole(Role role) {
        List<Long> ids = role == Role.MEMBER ? members.findActiveIds() : roles.findActiveMemberIdsWithRole(role);
        return ids.stream().map(MemberId::new).toList();
    }

    /** (Unscoped) A Team, retired or not, for another module's rule. */
    public TeamSummary team(TeamId id) {
        return team(id.value()).toSummary();
    }

    // ---------------------------------------------------------------- Rosters and profiles

    /**
     * A Team's Members, active or not, by name: for the Team's own Manager, a Capacity Planner, or
     * an Administrator. Another Team's Manager is told the Team does not exist.
     */
    @PreAuthorize("hasAnyAuthority('MANAGER', 'PLANNER', 'ADMIN')")
    public Page<MemberSummary> roster(Actor actor, TeamId teamId, int page, int size) {
        if (!actor.has(Role.PLANNER) && !actor.has(Role.ADMIN) && !actor.teamId().equals(teamId)) {
            throw teamNotFound(teamId.value());
        }
        Team team = team(teamId.value());
        return members.findByTeamId(team.id(), PageRequest.of(page, size, Sort.by("name", "id")))
                .map(member -> summaryOf(member, team));
    }

    /** A Member's profile, for the Member, their own Manager, a Capacity Planner, or an Administrator. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public MemberProfile profile(Actor actor, MemberId id) {
        return profileOf(visible(actor, id));
    }

    /**
     * Replaces the caller's skills with these, each from the skill list. A retired skill may be kept
     * by a Member who already holds it but not newly chosen.
     */
    @PreAuthorize("hasAuthority('MEMBER')")
    @Transactional
    public MemberProfile updateSkills(Actor actor, SkillsUpdate update) {
        Member member = member(actor.memberId());
        Set<Long> held = memberSkills.skillIdsOf(member.id());
        Set<Long> wanted = update.skills().stream().map(SkillId::value).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, SkillRow> found = skills.findAllById(wanted).stream()
                .collect(Collectors.toMap(SkillRow::id, Function.identity()));
        for (Long id : wanted) {
            SkillRow skill = found.get(id);
            if (skill == null) {
                throw new RuleViolation("VALIDATION_FAILED", "Skill " + id + " is not on the skill list.");
            }
            if (skill.retired() && !held.contains(id)) {
                throw new RuleViolation("SKILL_RETIRED",
                        skill.toSkill().name() + " has been retired and can no longer be chosen.");
            }
        }
        memberSkills.deleteByMemberId(member.id());
        memberSkills.saveAll(wanted.stream()
                .map(skillId -> new MemberSkillRow(member.id(), skillId, actor.memberId().value()))
                .toList());
        return profileOf(member);
    }

    /**
     * Replaces the Member's availability with these ranges, merging any that overlap or touch. Only
     * the Member and their own Manager may; a Capacity Planner or Administrator, who sees the
     * profile, is refused with FORBIDDEN, and anyone else is told the Member does not exist.
     */
    @PreAuthorize("hasAuthority('MEMBER')")
    @Transactional
    public MemberProfile updateAvailability(Actor actor, MemberId id, AvailabilityUpdate update) {
        Member member = visible(actor, id);
        if (!isSelfOrOwnManager(actor, member)) {
            throw new ForbiddenException("Only the Member and their own Manager change the Member's availability.");
        }
        List<DateRange> ranges = new ArrayList<>();
        for (AvailabilityUpdate.Range range : update.ranges()) {
            if (range.from().isAfter(range.to())) {
                throw new RuleViolation("RANGE_INVALID",
                        "The range from " + range.from() + " to " + range.to() + " ends before it starts.");
            }
            ranges.add(new DateRange(range.from(), range.to()));
        }
        availability.deleteByMemberId(member.id());
        availability.saveAll(Availability.merge(ranges).stream()
                .map(range -> new AvailabilityRangeRow(member.id(), range, actor.memberId().value()))
                .toList());
        return profileOf(member);
    }

    /**
     * (Unscoped) True when the Member is active and one of their ranges contains every day of the
     * window. False for a deactivated or unknown Member. Loans are not considered: Allocation owns them.
     */
    public boolean isAvailable(MemberId id, DateRange window) {
        return availability.activeAndAvailable(id.value(), window.from(), window.to());
    }

    /**
     * (Unscoped) The active Members outside {@code excludeTeam} who hold every one of the skills and
     * are available for the whole window, by id. An empty skill set asks for no particular skill.
     * Loans are not considered: Allocation owns them.
     */
    public Set<MemberId> availableWithSkills(Set<SkillId> skillIds, DateRange window, TeamId excludeTeam) {
        Collection<Long> ids = skillIds.stream().map(SkillId::value).collect(Collectors.toSet());
        return availability.activeAvailableWithSkills(ids.isEmpty() ? NO_SKILL : ids, ids.size(),
                        window.from(), window.to(), excludeTeam.value())
                .stream().map(MemberId::new).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---------------------------------------------------------------- The skill list

    /** The skill list by name: the offered skills, or with {@code includeRetired} every skill. */
    @PreAuthorize("hasAuthority('MEMBER')")
    public List<Skill> skills(boolean includeRetired) {
        return (includeRetired ? skills.findAllByName() : skills.findOfferedByName())
                .stream().map(SkillRow::toSkill).toList();
    }

    /** Adds a skill whose name no skill has, ignoring case. */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public Skill addSkill(Actor actor, NewSkill skill) {
        String name = skill.name().trim();
        if (skills.nameTaken(name)) {
            throw new RuleViolation("SKILL_NAME_TAKEN", "A skill named " + name + " is already on the list.");
        }
        return skills.save(new SkillRow(name, actor.memberId().value())).toSkill();
    }

    /** Retires a skill: Members who hold it keep it, but it is no longer offered. */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public Skill retireSkill(SkillId id) {
        SkillRow skill = skills.findById(id.value())
                .orElseThrow(() -> new NotFoundException("Skill " + id.value() + " does not exist."));
        skill.retire(Instant.now(clock));
        return skill.toSkill();
    }

    // ---------------------------------------------------------------- Administration

    /**
     * Moves a Member to another Team. A Team's Manager may leave only as its last active Member,
     * and then leaves the role behind; the target Team must not be retired.
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public MemberSummary moveToTeam(MemberId id, TeamMove move) {
        Member member = member(id);
        Team target = team(move.teamId().value());
        if (target.retired()) {
            throw new RuleViolation("TEAM_RETIRED", "The Team has been retired and takes no Members.");
        }
        Team current = team(member.teamId());
        if (current.id().equals(target.id())) {
            return summaryOf(member, current);
        }
        if (member.id().equals(current.managerMemberId())) {
            if (members.countByTeamIdAndActiveTrue(current.id()) > 1) {
                throw new RuleViolation("TEAM_NEEDS_MANAGER",
                        "The Team's Manager can leave only as its last Member; grant the role to a successor first.");
            }
            current.clearManager();
            roles.deleteById(new MemberRole.Key(member.id(), Role.MANAGER));
            roles.flush();
        }
        member.moveTo(target.id());
        return summaryOf(member, target);
    }

    // ---------------------------------------------------------------- Helpers

    private Member member(MemberId id) {
        return members.findById(id.value()).orElseThrow(() -> memberNotFound(id));
    }

    private Team team(Long id) {
        return teams.findById(id).orElseThrow(() -> teamNotFound(id));
    }

    /** The Member, if the caller may see their profile; otherwise reported as not existing. */
    private Member visible(Actor actor, MemberId id) {
        Member member = member(id);
        if (!isSelfOrOwnManager(actor, member) && !actor.has(Role.PLANNER) && !actor.has(Role.ADMIN)) {
            throw memberNotFound(id);
        }
        return member;
    }

    private static boolean isSelfOrOwnManager(Actor actor, Member member) {
        return member.id().equals(actor.memberId().value())
                || actor.has(Role.MANAGER) && member.teamId().equals(actor.teamId().value());
    }

    private MemberSummary summaryOf(Member member, Team team) {
        return new MemberSummary(new MemberId(member.id()), member.name(), member.username(),
                team.toSummary(), member.active(), roles.rolesOf(member.id()));
    }

    private MemberProfile profileOf(Member member) {
        Team team = teams.findById(member.teamId()).orElseThrow();
        List<Skill> held = skills.findAllById(memberSkills.skillIdsOf(member.id())).stream()
                .map(SkillRow::toSkill).sorted(BY_NAME).toList();
        List<DateRange> ranges = availability.findByMemberIdOrderByFromDate(member.id()).stream()
                .map(AvailabilityRangeRow::toRange).toList();
        return new MemberProfile(new MemberId(member.id()), member.name(), member.email(), team.toSummary(), held, ranges);
    }

    private static NotFoundException memberNotFound(MemberId id) {
        return new NotFoundException("Member " + id.value() + " does not exist.");
    }

    private static NotFoundException teamNotFound(Long id) {
        return new NotFoundException("Team " + id + " does not exist.");
    }
}
