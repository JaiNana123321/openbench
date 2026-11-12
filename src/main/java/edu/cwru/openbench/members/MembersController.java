package edu.cwru.openbench.members;

import edu.cwru.openbench.common.Actor;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.PageResponse;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.TeamId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Members endpoints (design Table "Endpoints by module"). A controller binds the request, takes
 * the caller as an {@code Actor} parameter when the service needs one, calls one service operation,
 * and returns its result; role checks and rules live in the service. In member paths {@code me}
 * stands for the caller's own id.
 */
@RestController
@RequestMapping("/api")
class MembersController {

    private final MemberService members;

    MembersController(MemberService members) {
        this.members = members;
    }

    @GetMapping("/teams")
    List<TeamSummary> teams() {
        return members.teams();
    }

    @GetMapping("/teams/{id}/members")
    PageResponse<MemberSummary> roster(
            Actor actor,
            @PathVariable TeamId id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return PageResponse.of(members.roster(actor, id, page, size));
    }

    @GetMapping("/members/me/profile")
    MemberProfile myProfile(Actor actor) {
        return members.profile(actor, actor.memberId());
    }

    /** The Members of other Teams a Person line may name. */
    @GetMapping("/members/lendable")
    List<MemberSummary> lendable(Actor actor) {
        return members.lendable(actor);
    }

    @GetMapping("/members/{id}/profile")
    MemberProfile profile(Actor actor, @PathVariable MemberId id) {
        return members.profile(actor, id);
    }

    @PutMapping("/members/me/skills")
    MemberProfile updateSkills(Actor actor, @Valid @RequestBody SkillsUpdate update) {
        return members.updateSkills(actor, update);
    }

    @PutMapping("/members/me/availability")
    MemberProfile updateMyAvailability(Actor actor, @Valid @RequestBody AvailabilityUpdate update) {
        return members.updateAvailability(actor, actor.memberId(), update);
    }

    @PutMapping("/members/{id}/availability")
    MemberProfile updateAvailability(Actor actor, @PathVariable MemberId id, @Valid @RequestBody AvailabilityUpdate update) {
        return members.updateAvailability(actor, id, update);
    }

    @GetMapping("/skills")
    List<Skill> skills(@RequestParam(defaultValue = "false") boolean includeRetired) {
        return members.skills(includeRetired);
    }

    @PostMapping("/admin/skills")
    ResponseEntity<Skill> addSkill(Actor actor, @Valid @RequestBody NewSkill skill) {
        Skill added = members.addSkill(actor, skill);
        return ResponseEntity.created(URI.create("/api/skills/" + added.id().value())).body(added);
    }

    @PostMapping("/admin/skills/{id}/retire")
    Skill retireSkill(@PathVariable SkillId id) {
        return members.retireSkill(id);
    }

    @PostMapping("/admin/members")
    ResponseEntity<MemberSummary> createMember(Actor actor, @Valid @RequestBody NewMember member) {
        MemberSummary created = members.createMember(actor, member);
        return ResponseEntity.created(URI.create("/api/members/" + created.id().value() + "/profile")).body(created);
    }

    @PostMapping("/admin/members/{id}/deactivate")
    MemberSummary deactivate(Actor actor, @PathVariable MemberId id) {
        return members.deactivate(actor, id);
    }

    @PostMapping("/admin/members/{id}/move")
    MemberSummary moveToTeam(Actor actor, @PathVariable MemberId id, @Valid @RequestBody TeamMove move) {
        return members.moveToTeam(actor, id, move);
    }

    @PostMapping("/admin/roles")
    MemberSummary grantRole(Actor actor, @Valid @RequestBody RoleGrant grant) {
        return members.grantRole(actor, grant);
    }

    @DeleteMapping("/admin/roles/{memberId}/{role}")
    MemberSummary revokeRole(Actor actor, @PathVariable MemberId memberId, @PathVariable Role role) {
        return members.revokeRole(actor, memberId, role);
    }

    /** Every Team with its Manager and active Members, retired ones included: the Administrator's Teams screen. */
    @GetMapping("/admin/teams")
    List<TeamOverview> teamOverview() {
        return members.teamOverview();
    }

    @PostMapping("/admin/teams")
    ResponseEntity<TeamOverview> createTeam(Actor actor, @Valid @RequestBody TeamName team) {
        TeamOverview created = members.createTeam(actor, team);
        return ResponseEntity.created(URI.create("/api/teams/" + created.id().value() + "/members")).body(created);
    }

    @PutMapping("/admin/teams/{id}")
    TeamOverview renameTeam(Actor actor, @PathVariable TeamId id, @Valid @RequestBody TeamName team) {
        return members.renameTeam(actor, id, team);
    }

    @PostMapping("/admin/teams/{id}/retire")
    TeamOverview retireTeam(Actor actor, @PathVariable TeamId id) {
        return members.retireTeam(actor, id);
    }

    /** The record of the Administrators' changes to Members, Teams, and roles, newest first. */
    @GetMapping("/admin/changes")
    PageResponse<AdminChange> changes(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return PageResponse.of(members.changes(page, size));
    }
}
