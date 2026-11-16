package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The rules of service tiers (issue #23; SRS Metered.Tier.Define, Metered.Tier.Bump) beyond the test
 * cases: the setting's refusals, where a tier may be set, and when a tier does not Bump.
 */
class TierRulesTest extends TierWorldTest {

    @Test
    @DisplayName("Tiers: a premium below 1.0, a duplicate name or premium, a tier on a non-Metered type, and an unknown id are refused; nothing is logged")
    void refusesInvalidTiers() throws Exception {
        String key = "tiers." + gpu;
        setTiers(gpu, "[{\"name\": \"Cheap\", \"premium\": 0.9}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        setTiers(gpu, "[{\"name\": \"Express\", \"premium\": 1.5}, {\"name\": \"express\", \"premium\": 2}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        setTiers(gpu, "[{\"name\": \"Express\", \"premium\": 1.5}, {\"name\": \"Rush\", \"premium\": 1.50}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        setTiers(gpu, "[{\"name\": \" \", \"premium\": 1.5}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        setTiers(gpu, "[{\"id\": " + Long.MAX_VALUE + ", \"name\": \"Express\", \"premium\": 1.5}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        setTiers(room, "[{\"name\": \"Express\", \"premium\": 1.5}]")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CLASS_MISMATCH"));
        long unpriced = testData().resourceType(unique("Storage"), "METERED");
        setTiers(unpriced, "[{\"name\": \"Express\", \"premium\": 1.5}]")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNIT_PRICE_REQUIRED"));
        setTiers(gpu, "{\"name\": \"Express\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM setting_change WHERE key = ?", Integer.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_tier WHERE type_id = ?", Integer.class, gpu)).isZero();
    }

    @Test
    @DisplayName("Tiers: a premium edit affects Draft Quotes but never a locked one; a tier a line refers to cannot be removed")
    void editsPremiumsAndRefusesRemovingATierInUse() throws Exception {
        LocalDate d = dayD();
        long[] tiers = standardAndExpress();
        long[] locked = submittedGpu(bob, 4, at(d, 10), at(d, 12), tiers[1]);
        long draft = createDraft(as(alice));
        gpuLine(as(alice), draft, 4, at(d, 13), at(d, 15), tiers[1]);

        setTiers(gpu, "[{\"id\": " + tiers[0] + ", \"name\": \"Standard\", \"premium\": 1.0}, {\"id\": " + tiers[1]
                + ", \"name\": \"Express\", \"premium\": 2}]")
                .andExpect(status().isOk());
        mvc.perform(get("/api/requests/{id}/draft", draft).with(as(alice)))
                .andExpect(jsonPath("$.lines[0].quote").value(120.00));
        detail(as(bob), locked[0]).andExpect(jsonPath("$.lines[0].line.quote").value(90.00));
        assertThat(jdbc.queryForList("SELECT new_value FROM setting_change WHERE key = ? ORDER BY id", String.class,
                "tiers." + gpu)).containsExactly("Standard x1; Express x1.5", "Standard x1; Express x2");

        // Express is used by both lines: removing it is refused, and nothing changes.
        setTiers(gpu, "[{\"id\": " + tiers[0] + ", \"name\": \"Standard\", \"premium\": 1.0}]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIER_IN_USE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_tier WHERE type_id = ?", Integer.class, gpu))
                .isEqualTo(2);
        // Standard is not used: removing it is allowed.
        setTiers(gpu, "[{\"id\": " + tiers[1] + ", \"name\": \"Express\", \"premium\": 2}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing['" + gpu + "'].tiers.length()").value(1));
    }

    @Test
    @DisplayName("Lines: a tier on an Exclusive line or of another type is VALIDATION_FAILED; an edit sets or clears the tier; Priority stays Exclusive-only")
    void offersTiersOnMeteredLinesOnly() throws Exception {
        LocalDate d = dayD();
        long[] tiers = standardAndExpress();
        long otherPool = testData().resourceType(unique("TPU"), "METERED");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price) VALUES (?, 1.00)", otherPool);
        jdbc.update("INSERT INTO service_tier (type_id, name, premium) VALUES (?, 'Express', 3)", otherPool);
        long otherTier = jdbc.queryForObject("SELECT id FROM service_tier WHERE type_id = ?", Long.class, otherPool);

        long draft = createDraft(as(alice));
        addTieredLine(as(alice), draft, room, 1, at(d, 10), at(d, 12), tiers[1])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("tierId"));
        addTieredLine(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), otherTier)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        long line = gpuLine(as(alice), draft, 4, at(d, 10), at(d, 12), null);
        editLine(draft, line, at(d, 10), tiers[1])
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tierName").value("Express"))
                .andExpect(jsonPath("$.quote").value(90.00));
        editLine(draft, line, at(d, 10), otherTier)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        editLine(draft, line, at(d, 10), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").doesNotExist())
                .andExpect(jsonPath("$.quote").value(60.00));
        setPriority(as(alice), draft, line, true)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRIORITY_NOT_OFFERED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_line WHERE request_id = ?", Integer.class, draft))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Bump: the plan Bumps only what the line needs, the latest-submitted of the lowest tier first")
    void bumpsTheLatestSubmittedLowestTierLineOnly() throws Exception {
        LocalDate d = dayD();
        long[] tiers = standardAndExpress();
        gpuBumpCutoff(24);
        // Oscar's untiered 6 units, submitted first; Bob's Standard 6 units; Oscar's untiered 4 units, last.
        long[] first = submittedGpu(oscar, 6, at(d, 10), at(d, 12), null);
        long[] standard = submittedGpu(bob, 6, at(d, 10), at(d, 12), tiers[0]);
        long[] last = submittedGpu(oscar, 4, at(d, 10), at(d, 12), null);

        long[] express = submittedGpu(alice, 4, at(d, 10), at(d, 12), tiers[1]);
        detail(as(alice), express[0]).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        assertThat(meteredStates(last[1])).containsExactly("BUMPED");
        assertThat(meteredStates(first[1])).containsExactly("ALLOCATED");
        assertThat(meteredStates(standard[1])).containsExactly("ALLOCATED");
        assertThat(peakLiveUnits(gpu, at(d, 10), at(d, 12))).isEqualTo(16);
    }

    @Test
    @DisplayName("Bump: equal tiers never Bump each other, and a type without a bump cutoff never Bumps")
    void equalTiersAndNoCutoffNeverBump() throws Exception {
        LocalDate d = dayD();
        long[] tiers = standardAndExpress();
        // No bump cutoff yet: Express waits behind a full pool of Standard.
        long[] full = submittedGpu(oscar, 16, at(d, 10), at(d, 12), tiers[0]);
        long[] noCutoff = submittedGpu(alice, 4, at(d, 10), at(d, 12), tiers[1]);
        detail(as(alice), noCutoff[0])
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("POOL_FULL"));
        assertThat(meteredStates(full[1])).containsExactly("ALLOCATED");

        // With a cutoff, Standard still never Bumps Standard.
        gpuBumpCutoff(24);
        long[] equal = submittedGpu(alice, 4, at(d, 13), at(d, 15), tiers[0]);
        long[] fullLater = submittedGpu(oscar, 16, at(d, 13), at(d, 15), tiers[0]);
        assertThat(meteredStates(equal[1])).containsExactly("ALLOCATED");
        detail(as(oscar), fullLater[0]).andExpect(jsonPath("$.request.state").value("WAITING"));
        assertThat(meteredStates(fullLater[1])).isEmpty();
        assertThat(refundsOf(full[1])).isEmpty();
        assertThat(refundsOf(equal[1])).isEmpty();
    }

    @Test
    @DisplayName("Bump: a lower-tier line starting inside the bump cutoff is not Bumped; the higher tier waits")
    void doesNotBumpInsideTheCutoff() throws Exception {
        Instant h = hourH();
        Instant h2 = h.plusSeconds(2 * 3600);
        long[] tiers = standardAndExpress();
        gpuBumpCutoff(24);
        long[] full = submittedGpu(oscar, 16, h, h2, tiers[0]);

        long[] express = submittedGpu(alice, 4, h, h2, tiers[1]);
        detail(as(alice), express[0])
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("POOL_FULL"));
        assertThat(meteredStates(full[1])).containsExactly("ALLOCATED");
        assertThat(refundsOf(full[1])).isEmpty();
        assertThat(noticesOf(world.oscar().id(), "BUMPED")).isEmpty();
    }

    @Test
    @DisplayName("Bump: no plan when the line would still exceed its own Team's Quota")
    void noBumpPastTheTeamsQuota() throws Exception {
        LocalDate d = dayD();
        long[] tiers = standardAndExpress();
        gpuBumpCutoff(24);
        jdbc.update("INSERT INTO quota (team_id, type_id, max_units) VALUES (?, ?, 8)", platform, gpu);
        long[] mine = submittedGpu(alice, 6, at(d, 10), at(d, 12), tiers[0]);
        long[] others = submittedGpu(oscar, 10, at(d, 10), at(d, 12), tiers[0]);

        // Platform holds 6 of its 8; the plan would Bump Oscar's later Standard line, but 4 more Express
        // units would still exceed Platform's Quota, so there is no plan.
        long[] express = submittedGpu(alice, 4, at(d, 10), at(d, 12), tiers[1]);
        detail(as(alice), express[0]).andExpect(jsonPath("$.request.state").value("WAITING"));
        assertThat(meteredStates(mine[1])).containsExactly("ALLOCATED");
        assertThat(meteredStates(others[1])).containsExactly("ALLOCATED");
    }

    private ResultActions editLine(long draft, long line, Instant start, Long tier)
            throws Exception {
        return mvc.perform(put("/api/requests/{id}/lines/{line}", draft, line).with(as(alice))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": 4, \"start\": \"" + start + "\", \"end\": \"" + start.plusSeconds(7200)
                        + "\", \"tierId\": " + tier + "}"));
    }
}
