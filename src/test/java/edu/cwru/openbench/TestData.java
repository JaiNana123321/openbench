package edu.cwru.openbench;

import edu.cwru.openbench.common.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/**
 * Rows for integration tests, written with SQL as the schema owner. The database is shared by every
 * test class, so names are made unique with {@link #unique(String)}.
 */
public final class TestData {

    /** The password of every Member created here. */
    public static final String PASSWORD = "secret";

    private static final String PASSWORD_HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final JdbcTemplate jdbc;

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A name no other test uses, such as {@code alice-17}. */
    public static String unique(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet();
    }

    public long team(String name) {
        return jdbc.queryForObject("INSERT INTO team (name) VALUES (?) RETURNING id", Long.class, name);
    }

    /**
     * An active Member with password {@link #PASSWORD}, named after the username. Granting
     * {@link Role#MANAGER} also makes them the Team's Manager.
     */
    public long member(long teamId, String username, Role... roles) {
        long id = jdbc.queryForObject("""
                INSERT INTO member (team_id, name, email, username, password_hash)
                VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Long.class, teamId, username, username + "@test.example", username, PASSWORD_HASH);
        for (Role role : roles) {
            if (role == Role.MEMBER) {
                continue;
            }
            jdbc.update("INSERT INTO member_role (member_id, team_id, role) VALUES (?, ?, ?)", id, teamId, role.name());
            if (role == Role.MANAGER) {
                jdbc.update("UPDATE team SET manager_member_id = ? WHERE id = ?", id, teamId);
            }
        }
        return id;
    }

    public void deactivate(long memberId) {
        jdbc.update("UPDATE member SET active = false WHERE id = ?", memberId);
    }

    /** A one-day Period on a far-future day of its own, so Periods of different tests never overlap. */
    public long period() {
        LocalDate day = LocalDate.of(2200, 1, 1).plusDays(SEQUENCE.incrementAndGet());
        return jdbc.queryForObject(
                "INSERT INTO period (starts_on, ends_on) VALUES (?, ?) RETURNING id", Long.class, day, day);
    }

    // ---------------------------------------------------------------- Inventory

    /** A Resource Type of class {@code MATCHED}, {@code EXCLUSIVE}, or {@code METERED}, without attributes. */
    public long resourceType(String name, String allocationClass) {
        return jdbc.queryForObject("INSERT INTO resource_type (name, allocation_class) VALUES (?, ?) RETURNING id",
                Long.class, name, allocationClass);
    }

    /** An attribute of kind TEXT, NUMBER, or DATE, or LIST with the given allowed values. */
    public long attribute(long typeId, String name, String kind, String... allowedValues) {
        return jdbc.queryForObject("""
                INSERT INTO resource_type_attribute (type_id, name, kind, allowed_values)
                VALUES (?, ?, ?, ?::text[]) RETURNING id""",
                Long.class, typeId, name, kind,
                kind.equals("LIST") ? "{" + String.join(",", allowedValues) + "}" : null);
    }

    /** A Resource of the type with these units (1 for an Exclusive type), owned by the Member. */
    public long resource(long typeId, String name, long ownerId, int units) {
        return jdbc.queryForObject("""
                INSERT INTO resource (type_id, name, owner_member_id, units) VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, typeId, name, ownerId, units);
    }

    public void attributeValue(long resourceId, long attributeId, String value) {
        jdbc.update("INSERT INTO resource_attribute_value (resource_id, attribute_id, value) VALUES (?, ?, ?)",
                resourceId, attributeId, value);
    }

    // ---------------------------------------------------------------- Settings and Ledger

    /**
     * The Period containing the day, made as a one-day Period only when none exists, so every test
     * shares it. Code that posts Transactions needs the Period of today by the application's Clock:
     * {@code testData().periodContaining(LocalDate.now(clock))}.
     */
    public long periodContaining(LocalDate day) {
        // DO NOTHING also covers the exclusion constraint: a Period overlapping this day contains it.
        jdbc.update("INSERT INTO period (starts_on, ends_on) VALUES (?, ?) ON CONFLICT DO NOTHING", day, day);
        return jdbc.queryForObject(
                "SELECT id FROM period WHERE starts_on <= ? AND ends_on >= ?", Long.class, day, day);
    }

    /**
     * A line of a new Draft Request of the Team, on a new Exclusive Resource Type, for the Ledger's
     * {@code held_credit} and {@code transaction} rows, whose {@code line_id} must exist.
     */
    public long requestLine(long teamId) {
        long type = jdbc.queryForObject("INSERT INTO resource_type (name, allocation_class) VALUES (?, 'EXCLUSIVE') RETURNING id",
                Long.class, unique("Type"));
        long request = jdbc.queryForObject("INSERT INTO request (team_id) VALUES (?) RETURNING id", Long.class, teamId);
        return jdbc.queryForObject("""
                INSERT INTO request_line (request_id, type_id, allocation_class, starts_at, ends_at)
                VALUES (?, ?, 'EXCLUSIVE', '2200-01-01T09:00:00Z', '2200-01-01T10:00:00Z') RETURNING id""",
                Long.class, request, type);
    }

    // ---------------------------------------------------------------- Allocation

    /** A Request of the Team in state SUBMITTED, submitted at the instant. */
    public long submittedRequest(long teamId, Instant submittedAt) {
        return jdbc.queryForObject("""
                INSERT INTO request (team_id, state, submitted_at) VALUES (?, 'SUBMITTED', ?) RETURNING id""",
                Long.class, teamId, utc(submittedAt));
    }

    /** An Exclusive line of the Request for {@code quantity} instances of the type over the window. */
    public long exclusiveLine(long requestId, long typeId, int quantity, Instant startsAt, Instant endsAt) {
        return jdbc.queryForObject("""
                INSERT INTO request_line (request_id, type_id, allocation_class, quantity, starts_at, ends_at, state)
                VALUES (?, ?, 'EXCLUSIVE', ?, ?, ?, 'EVALUATED') RETURNING id""",
                Long.class, requestId, typeId, quantity, utc(startsAt), utc(endsAt));
    }

    /** A Metered line of the Request for {@code units} of the type over the window. */
    public long meteredLine(long requestId, long typeId, int units, Instant startsAt, Instant endsAt) {
        return jdbc.queryForObject("""
                INSERT INTO request_line (request_id, type_id, allocation_class, units, starts_at, ends_at, state)
                VALUES (?, ?, 'METERED', ?, ?, ?, 'EVALUATED') RETURNING id""",
                Long.class, requestId, typeId, units, utc(startsAt), utc(endsAt));
    }

    /** The Team may hold at most {@code maxUnits} of the Metered type concurrently. */
    public void quota(long teamId, long typeId, int maxUnits) {
        jdbc.update("INSERT INTO quota (team_id, type_id, max_units) VALUES (?, ?, ?)", teamId, typeId, maxUnits);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- Members: skills, availability, Matched data

    /**
     * The skill with this name, ignoring case, added to the list only when no skill has it, so every
     * test shares it. Never retire a skill found this way; retire one with a {@link #unique} name.
     */
    public long skill(String name) {
        jdbc.update("INSERT INTO skill (name) VALUES (?) ON CONFLICT DO NOTHING", name);
        return jdbc.queryForObject("SELECT id FROM skill WHERE lower(name) = lower(?)", Long.class, name);
    }

    /** The Member holds these skills besides any they hold already. */
    public void holds(long memberId, long... skillIds) {
        for (long skillId : skillIds) {
            jdbc.update("INSERT INTO member_skill (member_id, skill_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    memberId, skillId);
        }
    }

    /** Adds a range, inclusive at both ends, to the Member's availability; it must not touch another of theirs. */
    public void available(long memberId, LocalDate from, LocalDate to) {
        jdbc.update("INSERT INTO availability_range (member_id, from_date, to_date) VALUES (?, ?, ?)",
                memberId, from, to);
    }

    /** A Member of a test world: their id and the username to sign in with ({@code signedInAs}). */
    public record Login(long id, String username) {}

    /**
     * Item 5 of the test world (docs/test-cases, Section "Test World") on Teams of its own. See
     * {@link #matchedWorld(LocalDate)}. {@code d} is day D: the first Wednesday at least 14 days
     * after {@code today}.
     */
    public record MatchedWorld(
            long platform, long research, long operations,
            Login alice, Login ben, Login bob, Login rita, Login oscar, Login dave,
            long statistics, long java, long microscopy,
            LocalDate today, LocalDate d) {}

    /**
     * The Matched data of the test world: Teams Platform (Manager Alice Chen, Ben Ortiz), Research
     * (Manager Bob Patel, Rita Gomez), and Operations (Manager Oscar Reed, Dave Kim, Resource
     * Owner); the skill list Statistics, Java, Microscopy; Ben holds Java, Rita Statistics and
     * Microscopy, Dave Statistics, each available from {@code today} to D+60. Every call makes new
     * Teams and Members with unique names and usernames but the document's display names, and
     * reuses the shared skills by name. Other tests' Members hold the same skills, so check a
     * query's answer for this world's Members rather than comparing it whole.
     */
    public MatchedWorld matchedWorld(LocalDate today) {
        LocalDate d = today.plusDays(14).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.WEDNESDAY));
        long platform = team(unique("Platform"));
        long research = team(unique("Research"));
        long operations = team(unique("Operations"));
        long statistics = skill("Statistics");
        long java = skill("Java");
        long microscopy = skill("Microscopy");
        Login alice = named(platform, "alice", "Alice Chen", Role.MANAGER);
        Login ben = named(platform, "ben", "Ben Ortiz");
        Login bob = named(research, "bob", "Bob Patel", Role.MANAGER);
        Login rita = named(research, "rita", "Rita Gomez");
        Login oscar = named(operations, "oscar", "Oscar Reed", Role.MANAGER);
        Login dave = named(operations, "dave", "Dave Kim", Role.RESOURCE_OWNER);
        holds(ben.id(), java);
        holds(rita.id(), statistics, microscopy);
        holds(dave.id(), statistics);
        for (Login lendable : new Login[] {ben, rita, dave}) {
            available(lendable.id(), today, d.plusDays(60));
        }
        return new MatchedWorld(platform, research, operations, alice, ben, bob, rita, oscar, dave,
                statistics, java, microscopy, today, d);
    }

    private Login named(long teamId, String login, String name, Role... roles) {
        String username = unique(login);
        long id = member(teamId, username, roles);
        jdbc.update("UPDATE member SET name = ? WHERE id = ?", name, id);
        return new Login(id, username);
    }
}
