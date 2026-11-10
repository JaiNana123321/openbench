package edu.cwru.openbench.notifications;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.MemberId;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** In-app notifications (SRS CI-inapp): written by {@link Notifier}, read and marked read by their recipient only. */
class NotificationsTest extends PostgresTest {

    @Autowired
    private Notifier notifier;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String ben;
    private long benId;
    private String rita;
    private long ritaId;

    @BeforeEach
    void createTwoMembers() {
        long team = testData().team(unique("Platform"));
        ben = unique("ben");
        benId = testData().member(team, ben);
        rita = unique("rita");
        ritaId = testData().member(team, rita);
    }

    @Test
    @DisplayName("CI-inapp: a Member sees their own unread notices, newest first, paged")
    void listsTheCallersUnreadNoticesNewestFirst() throws Exception {
        movableClock.set(Instant.parse("2026-11-09T14:00:00Z"));
        notifier.notify(new MemberId(benId), NotificationKind.REQUEST_UPDATE, "Request 1 is Allocated.", "/requests/1");
        movableClock.set(Instant.parse("2026-11-09T15:00:00Z"));
        notifier.notify(new MemberId(benId), NotificationKind.PERIOD_ROLLOVER, "The Period has ended.", "/statement");
        notifier.notify(new MemberId(ritaId), NotificationKind.REQUEST_UPDATE, "Rita's notice.", null);

        mvc.perform(get("/api/notifications").with(signedInAs(ben)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].kind").value("PERIOD_ROLLOVER"))
                .andExpect(jsonPath("$.content[0].text").value("The Period has ended."))
                .andExpect(jsonPath("$.content[0].link").value("/statement"))
                .andExpect(jsonPath("$.content[0].recipient").value(benId))
                .andExpect(jsonPath("$.content[0].readAt").isEmpty())
                .andExpect(jsonPath("$.content[1].text").value("Request 1 is Allocated."))
                .andExpect(jsonPath("$.content[1].createdAt").value("2026-11-09T09:00:00-05:00"));

        mvc.perform(get("/api/notifications").param("page", "1").param("size", "1").with(signedInAs(ben)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].text").value("Request 1 is Allocated."));
    }

    @Test
    @DisplayName("A notice is visible only to its recipient: another Member's notice is not found")
    void hidesAnotherMembersNotice() throws Exception {
        long id = notice(benId, "Only for Ben.");

        mvc.perform(get("/api/notifications").with(signedInAs(rita)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/notifications/{id}/read", id).with(signedInAs(rita)).with(xsrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/notifications/{id}/read", Long.MAX_VALUE).with(signedInAs(rita)).with(xsrf()))
                .andExpect(status().isNotFound());

        assertThat(readAt(id)).isNull();
    }

    @Test
    @DisplayName("Marking a notice read sets read_at once and takes it off the unread list")
    void setsReadAtAtMostOnce() throws Exception {
        long id = notice(benId, "Read me.");
        movableClock.set(Instant.parse("2026-11-09T16:00:00Z"));

        String first = mvc.perform(post("/api/notifications/{id}/read", id).with(signedInAs(ben)).with(xsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.readAt").value("2026-11-09T11:00:00-05:00"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(first, "$.text")).isEqualTo("Read me.");

        movableClock.set(Instant.parse("2026-11-09T17:00:00Z"));
        mvc.perform(post("/api/notifications/{id}/read", id).with(signedInAs(ben)).with(xsrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").value("2026-11-09T11:00:00-05:00"));

        assertThat(readAt(id)).isEqualTo(Instant.parse("2026-11-09T16:00:00Z"));
        mvc.perform(get("/api/notifications").with(signedInAs(ben)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("The notification endpoints need a signed-in Member")
    void refusesAnonymousCallers() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A notice is written in the caller's transaction: it is gone when the caller rolls back")
    void rollsBackWithTheCaller() {
        transactionTemplate.executeWithoutResult(status -> {
            notifier.notify(new MemberId(benId), NotificationKind.REQUEST_UPDATE, "Never committed.", null);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE member_id = ?", Integer.class, benId))
                .isZero();
    }

    @Test
    @DisplayName("A notice needs its text")
    void refusesANoticeWithoutText() {
        assertThatThrownBy(() -> notifier.notify(new MemberId(benId), NotificationKind.REQUEST_UPDATE, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("No notice is deleted: the application role may update a notice but not delete one")
    void keepsEveryNotice() {
        assertThat(privilege("UPDATE")).isTrue();
        assertThat(privilege("DELETE")).isFalse();
    }

    private long notice(long member, String text) {
        notifier.notify(new MemberId(member), NotificationKind.REQUEST_UPDATE, text, "/requests/1");
        return jdbc.queryForObject("SELECT max(id) FROM notification WHERE member_id = ?", Long.class, member);
    }

    private Instant readAt(long id) {
        OffsetDateTime at = jdbc.queryForObject("SELECT read_at FROM notification WHERE id = ?", OffsetDateTime.class, id);
        return at == null ? null : at.toInstant();
    }

    private boolean privilege(String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT has_table_privilege('openbench_app', 'notification', ?)", Boolean.class, privilege));
    }
}
