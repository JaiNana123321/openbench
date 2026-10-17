package edu.cwru.openbench;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("Exclusive.Conflict, QA-correct: exclusive_occurrence_no_overlap")
class ExclusionConstraintTest extends PostgresTest {

    private static final Instant TEN = Instant.parse("2026-10-27T14:00:00Z");
    private static final Instant ELEVEN = TEN.plusSeconds(3600);
    private static final Instant TWELVE = TEN.plusSeconds(2 * 3600);
    private static final Instant ONE = TEN.plusSeconds(3 * 3600);

    private long team;
    private long request;
    private long line;
    private long room;

    @BeforeEach
    void createARoomAndARequestLine() {
        team = testData().team(unique("Team"));
        long owner = testData().member(team, unique("owner"));
        long type = jdbc.queryForObject("INSERT INTO resource_type (name, allocation_class) VALUES (?, 'EXCLUSIVE') RETURNING id",
                Long.class, unique("Room"));
        room = jdbc.queryForObject("INSERT INTO resource (type_id, name, owner_member_id) VALUES (?, 'Room A', ?) RETURNING id",
                Long.class, type, owner);
        request = jdbc.queryForObject("INSERT INTO request (team_id) VALUES (?) RETURNING id", Long.class, team);
        line = jdbc.queryForObject("""
                INSERT INTO request_line (request_id, type_id, allocation_class, starts_at, ends_at)
                VALUES (?, ?, 'EXCLUSIVE', ?, ?) RETURNING id""",
                Long.class, request, type, utc(TEN), utc(ONE));
    }

    @Test
    void refusesASecondLiveOverlappingOccurrence() {
        occurrence(TEN, TWELVE, "HELD");

        assertThatThrownBy(() -> occurrence(ELEVEN, ONE, "ALLOCATED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("exclusive_occurrence_no_overlap")
                .extracting(e -> e.getCause(), InstanceOfAssertFactories.throwable(SQLException.class))
                .extracting(SQLException::getSQLState)
                .isEqualTo("23P01");
    }

    @Test
    void allowsAnOverlappingOccurrenceThatIsNotLive() {
        occurrence(TEN, TWELVE, "ALLOCATED");

        occurrence(ELEVEN, ONE, "RELEASED");
        occurrence(ELEVEN, ONE, "BUMPED");
        occurrence(ELEVEN, ONE, "EXPIRED");

        assertThat(count()).isEqualTo(4);
    }

    @Test
    void allowsBackToBackOccurrences() {
        occurrence(TEN, TWELVE, "ALLOCATED");
        occurrence(TWELVE, ONE, "HELD");

        assertThat(count()).isEqualTo(2);
    }

    @Test
    void refusesARowThatBecomesLiveOverAnother() {
        occurrence(TEN, TWELVE, "ALLOCATED");
        long released = occurrence(ELEVEN, ONE, "RELEASED");

        assertThatThrownBy(() -> jdbc.update("UPDATE exclusive_occurrence SET state = 'HELD' WHERE id = ?", released))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("exclusive_occurrence_no_overlap");
    }

    private long occurrence(Instant start, Instant end, String state) {
        return jdbc.queryForObject("""
                INSERT INTO exclusive_occurrence (line_id, team_id, request_id, resource_id, starts_at, ends_at, state)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, line, team, request, room, utc(start), utc(end), state);
    }

    private Integer count() {
        return jdbc.queryForObject("SELECT count(*) FROM exclusive_occurrence WHERE resource_id = ?", Integer.class, room);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
