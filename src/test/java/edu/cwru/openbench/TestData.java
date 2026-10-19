package edu.cwru.openbench;

import edu.cwru.openbench.common.Role;
import java.time.LocalDate;
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
}
