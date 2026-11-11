package edu.cwru.openbench.settings;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.Credits;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.Role;
import edu.cwru.openbench.members.SignedInMember;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Two Capacity Planners change the same setting at the same moment. {@code update} serializes
 * changes with one advisory lock taken before the old value is read, so the log chains: the second
 * change's old value is the first one's new value, and the setting ends at the last new value.
 */
@DisplayName("Admin.Member.Create: concurrent changes of one setting leave a log whose values chain")
class SettingsConcurrencyTest extends PostgresTest {

    @Autowired
    private SettingsService settings;

    @Autowired
    private UserDetailsService users;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @RepeatedTest(5)
    @DisplayName("two Planners set one Unit Price at once; the old value of each logged change is the value it replaced")
    void chainsTheLoggedValues() throws Exception {
        long team = testData().team(unique("Operations"));
        String erin = unique("erin");
        testData().member(team, erin, Role.PLANNER);
        String frank = unique("frank");
        testData().member(team, frank, Role.PLANNER);
        long room = testData().resourceType(unique("Meeting room"), "EXCLUSIVE");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, 10.00)", room);
        ResourceTypeId type = new ResourceTypeId(room);

        race(setting(erin, new SettingChange.UnitPrice(type, Credits.of("11.00"))),
                setting(frank, new SettingChange.UnitPrice(type, Credits.of("12.00"))));

        List<String[]> logged = jdbc.query("SELECT old_value, new_value FROM setting_change WHERE key = ? ORDER BY id",
                (row, n) -> new String[] {row.getString(1), row.getString(2)}, "unit_price." + room);
        assertThat(logged).hasSize(2);
        assertThat(logged.get(0)[0]).isEqualTo("10.00");
        assertThat(logged.get(1)[0]).as("the second change replaced the first one's value").isEqualTo(logged.get(0)[1]);
        assertThat(jdbc.queryForObject("SELECT unit_price FROM type_pricing WHERE type_id = ?", BigDecimal.class, room))
                .isEqualByComparingTo(logged.get(1)[1]);
    }

    /** The change, made as the Member in a thread of its own. */
    private Callable<Void> setting(String username, SettingChange change) {
        SignedInMember member = (SignedInMember) users.loadUserByUsername(username);
        return () -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(member, null,
                    member.getAuthorities()));
            SecurityContextHolder.setContext(context);
            try {
                settings.update(member.actor(), change);
                return null;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    /** Runs the calls, released by one latch, and fails if either fails. */
    private void race(Callable<Void> first, Callable<Void> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Void>> attempts = new ArrayList<>();
        for (Callable<Void> call : List.of(first, second)) {
            attempts.add(threads.submit(() -> {
                ready.countDown();
                go.await();
                return call.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<Void> attempt : attempts) {
            attempt.get(30, TimeUnit.SECONDS);
        }
    }
}
