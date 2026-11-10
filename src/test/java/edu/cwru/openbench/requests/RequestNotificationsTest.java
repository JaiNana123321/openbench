package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The notification sentences of SRS Lifecycle.Allocate for the transitions that exist today: the
 * requesting Manager gets a {@code REQUEST_UPDATE} on every state change of a submitted or withdrawn
 * Request and on every dropped Optional line, linking to the Request.
 */
class RequestNotificationsTest extends DemoWorldTest {

    @Test
    @DisplayName("Lifecycle.Allocate: the Manager is told when a Request is Allocated, and sees it in their list")
    void tellsTheManagerOfAnAllocation() throws Exception {
        long request = createDraft(as(alice));
        line(as(alice), request, room, 1, tuesdayAt(10), tuesdayAt(12), roomA);
        submit(as(alice), request).andExpect(jsonPath("$.state").value("ALLOCATED"));

        assertThat(noticesTo(alice)).singleElement().satisfies(notice -> assertThat(notice)
                .containsEntry("kind", "REQUEST_UPDATE")
                .containsEntry("text", "Request " + request + " is Allocated; its Charges total 30.00 credits.")
                .containsEntry("link", "/requests/" + request));
        mvc.perform(get("/api/notifications").with(signedInAs(alice)))
                .andExpect(jsonPath("$.content[0].text").value("Request " + request + " is Allocated; its Charges total 30.00 credits."));
        assertThat(noticesTo(ben)).isEmpty();
    }

    @Test
    @DisplayName("Lifecycle.Allocate: the Manager is told when a Request waits, and when it is withdrawn")
    void tellsTheManagerOfWaitingAndWithdrawal() throws Exception {
        long taken = createDraft(as(alice));
        line(as(alice), taken, room, 1, tuesdayAt(10), tuesdayAt(12), roomA);
        submit(as(alice), taken).andExpect(jsonPath("$.state").value("ALLOCATED"));

        long waiting = createDraft(as(bob));
        line(as(bob), waiting, room, 1, tuesdayAt(10), tuesdayAt(12), roomA);
        submit(as(bob), waiting).andExpect(jsonPath("$.state").value("WAITING"));
        withdraw(as(bob), waiting).andExpect(jsonPath("$.state").value("WITHDRAWN"));

        assertThat(noticesTo(bob)).extracting(notice -> notice.get("text")).containsExactly(
                "Request " + waiting + " is Waiting: 1 Required line waits for capacity.",
                "Request " + waiting + " was withdrawn.");
    }

    @Test
    @DisplayName("Lifecycle.Allocate: the Manager is told of a dropped Optional line")
    void tellsTheManagerOfADroppedOptionalLine() throws Exception {
        long taken = createDraft(as(bob));
        line(as(bob), taken, room, 1, thursdayAt(10), thursdayAt(12), roomA);
        submit(as(bob), taken).andExpect(jsonPath("$.state").value("ALLOCATED"));

        long request = createDraft(as(alice));
        line(as(alice), request, room, 1, tuesdayAt(10), tuesdayAt(12), roomB);
        long optional = line(as(alice), request, room, 1, thursdayAt(10), thursdayAt(12), roomA);
        markOptional(as(alice), request, optional);
        submit(as(alice), request).andExpect(jsonPath("$.state").value("ALLOCATED"));

        List<Object> texts = noticesTo(alice).stream().map(notice -> notice.get("text")).toList();
        assertThat(texts).hasSize(2);
        assertThat((String) texts.get(0)).startsWith("An Optional line of Request " + request + " was Dropped: ");
        assertThat(texts.get(1)).isEqualTo("Request " + request + " is Allocated; its Charges total 30.00 credits.");
    }

    private List<Map<String, Object>> noticesTo(String username) {
        return jdbc.queryForList("""
                SELECT n.kind, n.text, n.link FROM notification n JOIN member m ON m.id = n.member_id
                WHERE m.username = ? ORDER BY n.id""", username);
    }
}
