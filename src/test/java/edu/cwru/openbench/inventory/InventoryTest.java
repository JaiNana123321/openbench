package edu.cwru.openbench.inventory;

import static edu.cwru.openbench.TestData.unique;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import edu.cwru.openbench.common.Role;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Base of the Inventory's HTTP tests: a Team with an Administrator, two Resource Owners, and a plain
 * Member, all new for each test, plus shorthands for the JSON calls they make.
 */
abstract class InventoryTest extends PostgresTest {

    protected long team;
    protected String admin;
    protected String owner;
    protected long ownerId;
    protected String otherOwner;
    protected long otherOwnerId;
    protected String member;
    protected long memberId;

    @BeforeEach
    void createTheTeam() {
        team = testData().team(unique("Operations"));
        admin = unique("carol");
        testData().member(team, admin, Role.ADMIN);
        owner = unique("dave");
        ownerId = testData().member(team, owner, Role.RESOURCE_OWNER);
        otherOwner = unique("owen");
        otherOwnerId = testData().member(team, otherOwner, Role.RESOURCE_OWNER);
        member = unique("ben");
        memberId = testData().member(team, member);
    }

    protected ResultActions getAs(String username, String path) throws Exception {
        return mvc.perform(get(path).with(signedInAs(username)));
    }

    protected ResultActions postAs(String username, String path, String json) throws Exception {
        return mvc.perform(post(path).with(signedInAs(username)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions postAs(String username, String path) throws Exception {
        return mvc.perform(post(path).with(signedInAs(username)).with(xsrf()));
    }

    protected ResultActions putAs(String username, String path, String json) throws Exception {
        return mvc.perform(put(path).with(signedInAs(username)).with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** A problem+json refusal with this status and code, naming the request path. */
    protected static ResultMatcher refused(int status, String code) {
        return ResultMatcher.matchAll(
                status().is(status),
                content().contentType(MediaType.APPLICATION_PROBLEM_JSON),
                jsonPath("$.code").value(code),
                jsonPath("$.status").value(status),
                jsonPath("$.type").value("/problems/" + code.toLowerCase().replace('_', '-')),
                jsonPath("$.title").isNotEmpty(),
                jsonPath("$.detail").isNotEmpty(),
                jsonPath("$.instance").isNotEmpty());
    }
}
