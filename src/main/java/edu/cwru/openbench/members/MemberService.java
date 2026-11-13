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
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final AdminChangeRepository changes;
    private final PasswordEncoder passwords;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    MemberService(MemberRepository members, TeamRepository teams, MemberRoleRepository roles, SkillRepository skills,
            MemberSkillRepository memberSkills, AvailabilityRangeRepository availability, AdminChangeRepository changes,
            PasswordEncoder passwords, ApplicationEventPublisher events, Clock clock) {
        this.members = members;
        this.teams = teams;
        this.roles = roles;
        this.skills = skills;
        this.memberSkills = memberSkills;
        this.availability = availability;
        this.changes = changes;
        this.passwords = passwords;
        this.events = events;
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
     * POST /api/admin/members: a new active Member of a Team that is not retired, holding no role
     * but {@code MEMBER}. The password is stored only as its bcrypt hash.
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public MemberSummary createMember(Actor actor, NewMember cmd) {
        Team team = team(cmd.teamId().value());
        if (team.retired()) {
            throw teamRetired();
        }
        String username = cmd.username().trim();
        String email = cmd.email().trim();
        if (members.existsByUsername(username)) {
            throw new RuleViolation("USERNAME_TAKEN", "The username " + username + " is already taken.");
        }
        if (members.existsByEmailIgnoreCase(email)) {
            throw new RuleViolation("EMAIL_TAKEN", "The email " + email + " already belongs to a Member.");
        }
        Member member = members.save(new Member(team.id(), cmd.name().trim(), email, username,
                passwords.encode(cmd.password()), actor.memberId().value()));
        record(actor, AdminChangeKind.MEMBER_CREATED, member.id(), team.id(), null, team.name());
        return summaryOf(member, team);
    }

    /**
     * POST /api/admin/members/{id}/deactivate: the Member can no longer sign in, is on no Shortlist,
     * and their open sessions end; their Requests, Loans, and Transactions stay. A Team's Manager is
     * deactivated only as its last active Member, and then leaves the role behind; the last active
     * Administrator is never deactivated. Deactivating an inactive Member changes nothing.
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public MemberSummary deactivate(Actor actor, MemberId id) {
        Member member = member(id);
        Team team = lockedTeam(member.teamId());
        if (!member.active()) {
            return summaryOf(member, team);
        }
        boolean manager = member.id().equals(team.managerMemberId());
        if (manager && members.countByTeamIdAndActiveTrue(team.id()) > 1) {
            throw new RuleViolation("TEAM_NEEDS_MANAGER",
                    "The Team's Manager can be deactivated only as its last Member; grant the role to a successor first.");
        }
        if (roles.rolesOf(member.id()).contains(Role.ADMIN) && isLastActiveAdministrator(member)) {
            throw lastAdministrator();
        }
        if (manager) {
            leaveManagerRole(actor, member, team);
        }
        member.deactivate();
        record(actor, AdminChangeKind.MEMBER_DEACTIVATED, member.id(), team.id(), "active", "inactive");
        events.publishEvent(new MemberAccessChanged(id));
        return summaryOf(member, team);
    }

    /**
     * POST /api/admin/members/{id}/move: moves a Member to another Team. A Team's Manager may leave
     * only as its last active Member, and then leaves the role behind; the target Team must not be
     * retired. The Member's other roles move with them, and their open sessions end, because the
     * Team in a session decides which Requests, Statements, and rosters the Member sees.
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public MemberSummary moveToTeam(Actor actor, MemberId id, TeamMove move) {
        Member member = member(id);
        Team target = team(move.teamId().value());
        if (target.retired()) {
            throw teamRetired();
        }
        Team current = lockedTeam(member.teamId());
        if (current.id().equals(target.id())) {
            return summaryOf(member, current);
        }
        if (member.id().equals(current.managerMemberId())) {
            if (members.countByTeamIdAndActiveTrue(current.id()) > 1) {
                throw new RuleViolation("TEAM_NEEDS_MANAGER",
                        "The Team's Manager can leave only as its last Member; grant the role to a successor first.");
            }
            leaveManagerRole(actor, member, current);
        }
        member.moveTo(target.id());
        record(actor, AdminChangeKind.MEMBER_MOVED, member.id(), target.id(), current.name(), target.name());
        events.publishEvent(new MemberAccessChanged(id));
        return summaryOf(member, target);
    }

    /**
     * POST /api/admin/roles: grants an active Member a role. Granting {@code MANAGER} makes them their
     * Team's Manager, and the previous Manager loses the role in the same transaction, so the Team
     * has exactly one. The sessions of both end. Granting a role the Member holds changes nothing.
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public MemberSummary grantRole(Actor actor, RoleGrant grant) {
        Role role = storedRole(grant.role());
        Member member = member(grant.memberId());
        if (!member.active()) {
            throw new RuleViolation("MEMBER_INACTIVE", member.name() + " has been deactivated and holds no new role.");
        }
        Team team = lockedTeam(member.teamId());
        Set<Role> before = roles.rolesOf(member.id());
        if (before.contains(role)) {
            return summaryOf(member, team);
        }
        if (role == Role.MANAGER && team.managerMemberId() != null) {
            Member previous = members.findById(team.managerMemberId()).orElseThrow();
            leaveManagerRole(actor, previous, team);
            events.publishEvent(new MemberAccessChanged(new MemberId(previous.id())));
        }
        roles.saveAndFlush(new MemberRole(member.id(), role, team.id(), actor.memberId().value()));
        if (role == Role.MANAGER) {
            team.appointManager(member.id());
        }
        record(actor, AdminChangeKind.ROLE_GRANTED, member.id(), team.id(), rolesText(before),
                rolesText(roles.rolesOf(member.id())));
        events.publishEvent(new MemberAccessChanged(grant.memberId()));
        return summaryOf(member, team);
    }

    /**
     * DELETE /api/admin/roles/{memberId}/{role}: revokes a role. {@code MANAGER} is never revoked, only
     * passed to a successor by granting it; the last active Administrator keeps {@code ADMIN}. The
     * Member's sessions end. Revoking a role the Member does not hold changes nothing.
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public MemberSummary revokeRole(Actor actor, MemberId id, Role revoked) {
        Role role = storedRole(revoked);
        Member member = member(id);
        if (role == Role.MANAGER) {
            throw new RuleViolation("TEAM_NEEDS_MANAGER",
                    "A Team keeps its Manager; grant the role to a successor instead.");
        }
        Team team = team(member.teamId());
        Set<Role> before = roles.rolesOf(member.id());
        if (!before.contains(role)) {
            return summaryOf(member, team);
        }
        if (role == Role.ADMIN && member.active() && isLastActiveAdministrator(member)) {
            throw lastAdministrator();
        }
        roles.deleteById(new MemberRole.Key(member.id(), role));
        roles.flush();
        record(actor, AdminChangeKind.ROLE_REVOKED, member.id(), team.id(), rolesText(before),
                rolesText(roles.rolesOf(member.id())));
        events.publishEvent(new MemberAccessChanged(id));
        return summaryOf(member, team);
    }

    /** GET /api/admin/teams: every Team, retired ones included, by name, with its Manager and active Members. */
    @PreAuthorize("hasAuthority('ADMIN')")
    public List<TeamOverview> teamOverview() {
        return teams.findAllByOrderByName().stream().map(this::overviewOf).toList();
    }

    /** POST /api/admin/teams: a new Team, without Members; its name is unused, ignoring case. */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public TeamOverview createTeam(Actor actor, TeamName cmd) {
        String name = unusedTeamName(cmd.name(), -1L);
        Team team = teams.save(new Team(name, actor.memberId().value()));
        record(actor, AdminChangeKind.TEAM_CREATED, null, team.id(), null, name);
        return overviewOf(team);
    }

    /** PUT /api/admin/teams/{id}: renames a Team to a name no other Team has, ignoring case. */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public TeamOverview renameTeam(Actor actor, TeamId id, TeamName cmd) {
        Team team = team(id.value());
        String name = cmd.name().trim();
        if (name.equals(team.name())) {
            return overviewOf(team);
        }
        unusedTeamName(name, team.id());
        record(actor, AdminChangeKind.TEAM_RENAMED, null, team.id(), team.name(), name);
        team.rename(name);
        return overviewOf(team);
    }

    /**
     * POST /api/admin/teams/{id}/retire: a Team without active Members takes no more Members and is
     * no longer listed; its Account, Statements, and Requests stay readable. Retiring a retired Team
     * changes nothing.
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @Transactional
    public TeamOverview retireTeam(Actor actor, TeamId id) {
        Team team = lockedTeam(id.value());
        if (team.retired()) {
            return overviewOf(team);
        }
        long active = members.countByTeamIdAndActiveTrue(team.id());
        if (active > 0) {
            throw new RuleViolation("TEAM_NOT_EMPTY", team.name() + " still has " + active
                    + (active == 1 ? " active Member" : " active Members") + "; move or deactivate them first.");
        }
        team.retire(Instant.now(clock));
        record(actor, AdminChangeKind.TEAM_RETIRED, null, team.id(), "active", "retired");
        return overviewOf(team);
    }

    /** GET /api/admin/changes: the record of the Administrators' changes, newest first, paged. */
    @PreAuthorize("hasAuthority('ADMIN')")
    public Page<AdminChange> changes(int page, int size) {
        Page<AdminChangeRow> rows = changes.findAllByOrderByAtDescIdDesc(PageRequest.of(page, size));
        Set<Long> memberIds = new LinkedHashSet<>();
        Set<Long> teamIds = new LinkedHashSet<>();
        for (AdminChangeRow row : rows) {
            if (row.memberId() != null) {
                memberIds.add(row.memberId());
            }
            if (row.actorId() != null) {
                memberIds.add(row.actorId());
            }
            if (row.teamId() != null) {
                teamIds.add(row.teamId());
            }
        }
        Map<Long, String> memberNames = members.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Member::id, Member::name));
        Map<Long, String> teamNames = teams.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::id, Team::name));
        return rows.map(row -> new AdminChange(row.id(), row.kind(),
                row.memberId() == null ? null : new MemberId(row.memberId()), memberNames.get(row.memberId()),
                row.teamId() == null ? null : new TeamId(row.teamId()), teamNames.get(row.teamId()),
                row.oldValue(), row.newValue(),
                row.actorId() == null ? null : new MemberId(row.actorId()), memberNames.get(row.actorId()),
                row.at()));
    }

    /** The Manager leaves the role behind: the Team has none until it is granted again. Recorded. */
    private void leaveManagerRole(Actor actor, Member manager, Team team) {
        Set<Role> before = roles.rolesOf(manager.id());
        team.clearManager();
        roles.deleteById(new MemberRole.Key(manager.id(), Role.MANAGER));
        // Delete before any insert: Hibernate flushes inserts first, and one MANAGER row per Team is unique.
        roles.flush();
        record(actor, AdminChangeKind.ROLE_REVOKED, manager.id(), team.id(), rolesText(before),
                rolesText(roles.rolesOf(manager.id())));
    }

    private boolean isLastActiveAdministrator(Member member) {
        List<Long> administrators = roles.findActiveMemberIdsWithRole(Role.ADMIN);
        return administrators.size() <= 1 && administrators.contains(member.id());
    }

    private String unusedTeamName(String requested, Long except) {
        String name = requested.trim();
        if (teams.nameTaken(name, except)) {
            throw new RuleViolation("TEAM_NAME_TAKEN", "A Team named " + name + " already exists.");
        }
        return name;
    }

    private TeamOverview overviewOf(Team team) {
        Long manager = team.managerMemberId();
        return new TeamOverview(new TeamId(team.id()), team.name(), team.retired(),
                manager == null ? null : new MemberId(manager),
                manager == null ? null : members.findById(manager).map(Member::name).orElse(null),
                members.countByTeamIdAndActiveTrue(team.id()));
    }

    private void record(Actor actor, AdminChangeKind kind, Long memberId, Long teamId, String oldValue,
            String newValue) {
        changes.save(new AdminChangeRow(kind, memberId, teamId, oldValue, newValue, actor.memberId().value(),
                Instant.now(clock)));
    }

    /** The roles a Member holds when signed in, in the order of {@link Role}: "MEMBER, MANAGER". */
    private static String rolesText(Set<Role> stored) {
        EnumSet<Role> all = EnumSet.of(Role.MEMBER);
        all.addAll(stored);
        return all.stream().map(Role::name).collect(Collectors.joining(", "));
    }

    /** {@code MEMBER} is held by every active Member and never granted or revoked. */
    private static Role storedRole(Role role) {
        if (role == Role.MEMBER) {
            throw new RuleViolation("VALIDATION_FAILED",
                    "Every active Member holds MEMBER; it is neither granted nor revoked.");
        }
        return role;
    }

    private static RuleViolation teamRetired() {
        return new RuleViolation("TEAM_RETIRED", "The Team has been retired and takes no Members.");
    }

    private static RuleViolation lastAdministrator() {
        return new RuleViolation("LAST_ADMINISTRATOR",
                "This is the last active Administrator; grant the role to another Member first.");
    }

    /** The Team, locked until the transaction ends, so changes to its Manager are serialized. */
    private Team lockedTeam(Long id) {
        return teams.lockById(id).orElseThrow(() -> teamNotFound(id));
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

    /**
     * (Unscoped) The Team's current Manager, or empty for a Team without one or that does not exist.
     * Unlike {@link #managerOf}, it never throws, so a caller inside its own transaction (a job
     * notifying every Team) can skip a Team without marking that transaction for rollback.
     */
    public Optional<MemberId> findManagerOf(TeamId team) {
        return teams.findById(team.value()).map(Team::managerMemberId).map(MemberId::new);
    }

    // ---------------------------------------------------------------- Matched lines

    /** (Unscoped) {@link #summary}, or empty for an unknown Member, without throwing. */
    public Optional<MemberSummary> findSummary(MemberId id) {
        return members.findById(id.value())
                .map(member -> summaryOf(member, teams.findById(member.teamId()).orElseThrow()));
    }

    /**
     * (Unscoped) {@link #summary} of each known Member, by id, in three queries whatever their
     * number (requirement PE-shortlist); unknown ids are left out.
     */
    public Map<MemberId, MemberSummary> summaries(Collection<MemberId> ids) {
        List<Long> raw = ids.stream().map(MemberId::value).distinct().toList();
        if (raw.isEmpty()) {
            return Map.of();
        }
        List<Member> found = members.findAllById(raw);
        Map<Long, Team> byId = teams.findAllById(found.stream().map(Member::teamId).distinct().toList()).stream()
                .collect(Collectors.toMap(Team::id, Function.identity()));
        Map<Long, Set<Role>> held = roles.findByMemberIdIn(raw).stream().collect(Collectors.groupingBy(
                MemberRole::memberId, Collectors.mapping(MemberRole::role, Collectors.toSet())));
        Map<MemberId, MemberSummary> summaries = new java.util.LinkedHashMap<>();
        for (Member member : found) {
            summaries.put(new MemberId(member.id()), new MemberSummary(new MemberId(member.id()), member.name(),
                    member.username(), byId.get(member.teamId()).toSummary(), member.active(),
                    held.getOrDefault(member.id(), Set.of())));
        }
        return summaries;
    }

    /**
     * (Unscoped) The skills each Member holds, by name, in two queries whatever their number
     * (requirement PE-shortlist); a Member holding none maps to an empty list.
     */
    public Map<MemberId, List<Skill>> skillsOf(Collection<MemberId> ids) {
        List<Long> raw = ids.stream().map(MemberId::value).distinct().toList();
        if (raw.isEmpty()) {
            return Map.of();
        }
        List<MemberSkillRow> rows = memberSkills.findByMemberIdIn(raw);
        Map<Long, Skill> byId = skills.findAllById(rows.stream().map(MemberSkillRow::skillId).distinct().toList())
                .stream().map(SkillRow::toSkill).collect(Collectors.toMap(skill -> skill.id().value(), Function.identity()));
        Map<MemberId, List<Skill>> held = new java.util.LinkedHashMap<>();
        raw.forEach(id -> held.put(new MemberId(id), new ArrayList<>()));
        rows.forEach(row -> held.get(new MemberId(row.memberId())).add(byId.get(row.skillId())));
        held.replaceAll((member, list) -> list.stream().sorted(BY_NAME).toList());
        return held;
    }

    /** (Unscoped) The skills a Member holds, by name: what a Shortlist row shows. */
    public List<Skill> skillsOf(MemberId id) {
        return skills.findAllById(memberSkills.skillIdsOf(id.value())).stream()
                .map(SkillRow::toSkill).sorted(BY_NAME).toList();
    }

    /** (Unscoped) The skills with these ids, by name, retired or not; unknown ids are left out. */
    public List<Skill> skillsById(Collection<SkillId> ids) {
        return skills.findAllById(ids.stream().map(SkillId::value).toList()).stream()
                .map(SkillRow::toSkill).sorted(BY_NAME).toList();
    }

    /**
     * GET /api/members/lendable: the active Members of every other Team, by name, whom the caller's
     * Team could name on a Person line.
     */
    @PreAuthorize("hasAuthority('MANAGER')")
    public List<MemberSummary> lendable(Actor actor) {
        Map<Long, Team> byId = teams.findAll().stream().collect(Collectors.toMap(Team::id, Function.identity()));
        return members.findByActiveTrueAndTeamIdNotOrderByName(actor.teamId().value()).stream()
                .map(member -> summaryOf(member, byId.get(member.teamId())))
                .toList();
    }
}
