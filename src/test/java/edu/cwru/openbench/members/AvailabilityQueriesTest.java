package edu.cwru.openbench.members;

import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.TestData.MatchedWorld;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.MemberId;
import edu.cwru.openbench.common.SkillId;
import edu.cwru.openbench.common.TeamId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two questions Matched allocation asks of Members, on the Matched data of the test world.
 * Other tests' Members share the skills, so each answer is checked for this world's Members only.
 */
class AvailabilityQueriesTest extends PostgresTest {

    @Autowired
    private Clock clock;

    @Autowired
    private MemberService members;

    private MatchedWorld world;
    private MemberId ben;
    private MemberId rita;
    private MemberId dave;
    private DateRange window;

    @BeforeEach
    void createTheMatchedData() {
        world = testData().matchedWorld(LocalDate.now(clock));
        ben = new MemberId(world.ben().id());
        rita = new MemberId(world.rita().id());
        dave = new MemberId(world.dave().id());
        window = new DateRange(world.d(), world.d().plusDays(1));
    }

    @Test
    void aMemberIsAvailableOnlyForAWindowInsideOneRange() {
        LocalDate lastDay = world.d().plusDays(60);
        assertThat(members.isAvailable(dave, window)).isTrue();
        assertThat(members.isAvailable(dave, new DateRange(world.today(), lastDay))).isTrue();
        assertThat(members.isAvailable(dave, new DateRange(lastDay, lastDay.plusDays(1)))).isFalse();
        assertThat(members.isAvailable(dave, new DateRange(world.today().minusDays(1), world.today()))).isFalse();
        assertThat(members.isAvailable(new MemberId(world.alice().id()), window)).isFalse();
    }

    @Test
    void aDeactivatedMemberIsNeverAvailable() {
        testData().deactivate(dave.value());

        assertThat(members.isAvailable(dave, window)).isFalse();
        assertThat(members.availableWithSkills(Set.of(statistics()), window, new TeamId(world.platform())))
                .contains(rita)
                .doesNotContain(dave);
    }

    @Test
    void anUnknownMemberIsNotAvailable() {
        assertThat(members.isAvailable(new MemberId(Long.MAX_VALUE), window)).isFalse();
    }

    @Test
    void candidatesHoldEverySkillAndAreAvailableOutsideTheRequestingTeam() {
        TeamId platform = new TeamId(world.platform());
        TeamId research = new TeamId(world.research());
        Set<MemberId> world = Set.of(ben, rita, dave);

        assertThat(members.availableWithSkills(Set.of(statistics()), window, platform))
                .filteredOn(world::contains).containsExactlyInAnyOrder(rita, dave);
        assertThat(members.availableWithSkills(Set.of(statistics()), window, research))
                .filteredOn(world::contains).containsExactly(dave);
        assertThat(members.availableWithSkills(Set.of(statistics(), microscopy()), window, platform))
                .filteredOn(world::contains).containsExactly(rita);
        assertThat(members.availableWithSkills(Set.of(), window, platform))
                .filteredOn(world::contains).containsExactlyInAnyOrder(rita, dave);
    }

    @Test
    void aCandidateMustBeAvailableForEveryDayOfTheWindow() {
        DateRange pastTheRanges = new DateRange(world.d().plusDays(60), world.d().plusDays(61));
        assertThat(members.availableWithSkills(Set.of(statistics()), pastTheRanges, new TeamId(world.platform())))
                .doesNotContain(rita, dave);
    }

    private SkillId statistics() {
        return new SkillId(this.world.statistics());
    }

    private SkillId microscopy() {
        return new SkillId(this.world.microscopy());
    }
}
