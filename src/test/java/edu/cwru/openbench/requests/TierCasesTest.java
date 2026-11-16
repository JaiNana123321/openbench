package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The service-tier test cases of {@code docs/test-cases/sections/cases/07-metered.tex} (SRS
 * Metered.Tier.Define, Metered.Tier.Bump), step by step through the API.
 */
class TierCasesTest extends TierWorldTest {

    @Test
    @DisplayName("met-tier-define: the Planner defines GPU's tiers, logged; with Express, L1's Quote is 60.00 x 1.5 = 90.00")
    @Verifies("met-tier-define")
    void definesTiersThatMultiplyTheUnitPrice() throws Exception {
        LocalDate d = dayD();
        long erinId = jdbc.queryForObject("SELECT id FROM member WHERE username = ?", Long.class, erin);

        // 1. In Settings, define the two tiers for GPU: saved, and recorded with actor, old and new value, and time.
        setTiers(gpu, "[{\"name\": \"Standard\", \"premium\": 1.0}, {\"name\": \"Express\", \"premium\": 1.5}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing['" + gpu + "'].tiers.length()").value(2))
                .andExpect(jsonPath("$.pricing['" + gpu + "'].tiers[0].name").value("Standard"))
                .andExpect(jsonPath("$.pricing['" + gpu + "'].tiers[0].premium").value(1.0))
                .andExpect(jsonPath("$.pricing['" + gpu + "'].tiers[1].name").value("Express"))
                .andExpect(jsonPath("$.pricing['" + gpu + "'].tiers[1].premium").value(1.5));
        String key = "tiers." + gpu;
        mvc.perform(get("/api/settings/changes").with(as(erin)).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.key == '" + key + "')].oldValue").value(hasItem((Object) null)))
                .andExpect(jsonPath("$.content[?(@.key == '" + key + "')].newValue").value(hasItem("Standard x1; Express x1.5")))
                .andExpect(jsonPath("$.content[?(@.key == '" + key + "')].actor").value(hasItem((int) erinId)))
                .andExpect(jsonPath("$.content[?(@.key == '" + key + "')].at").isNotEmpty());
        long express = jdbc.queryForObject("SELECT id FROM service_tier WHERE type_id = ? AND name = 'Express'",
                Long.class, gpu);

        // 2. As alice, add L1 (GPU, 4 units, D 10:00-12:00, Express) to a Draft: the line has the tier, Quote 90.00.
        long draft = createDraft(as(alice));
        addTieredLine(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), express)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value(express))
                .andExpect(jsonPath("$.tierName").value("Express"))
                .andExpect(jsonPath("$.quote").value(90.00));
        // Pass criteria: the tier's premium multiplies the Unit Price; without it the same line is 60.00.
        addTieredLine(as(alice), draft, gpu, 4, at(d, 10), at(d, 12), null)
                .andExpect(jsonPath("$.tier").doesNotExist())
                .andExpect(jsonPath("$.quote").value(60.00));
        mvc.perform(get("/api/requests/{id}/draft", draft).with(as(alice)))
                .andExpect(jsonPath("$.lines[0].quote").value(90.00))
                .andExpect(jsonPath("$.requiredSum").value(150.00));
    }

    @Test
    @DisplayName("met-tier-bump: Express L1 Bumps Standard O1 (16 units, 240.00): a 240.00 Refund, head of the GPU waitlist, a notice")
    @Verifies("met-tier-bump")
    void aHigherTierBumpsALowerTier() throws Exception {
        LocalDate d = dayD();
        // Preconditions: the tiers of met-tier-define, a 24-hour bump cutoff for GPU, and oscar's Allocated
        // Standard line O1: GPU, 16 units, D 10:00-12:00 (240.00).
        long[] tiers = standardAndExpress();
        gpuBumpCutoff(24);
        long[] o1 = submittedGpu(oscar, 16, at(d, 10), at(d, 12), tiers[0]);
        detail(as(oscar), o1[0])
                .andExpect(jsonPath("$.request.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.quote").value(240.00));

        // 1. Submit a Request with L1 (GPU, 4 units, D 10:00-12:00, Express): Allocated, O1 Bumped to make room.
        long a = createDraft(as(alice));
        long l1 = gpuLine(as(alice), a, 4, at(d, 10), at(d, 12), tiers[1]);
        nextSecond();
        submit(as(alice), a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ALLOCATED"));
        detail(as(alice), a)
                .andExpect(jsonPath("$.lines[0].line.state").value("ALLOCATED"))
                .andExpect(jsonPath("$.lines[0].line.tierName").value("Express"))
                .andExpect(jsonPath("$.lines[0].line.quote").value(90.00))
                .andExpect(jsonPath("$.history[?(@.line == " + l1 + " && @.toState == 'ALLOCATED')].note")
                        .value(hasItem(containsString("Bumped line " + o1[1] + " of Request " + o1[0]))));

        // 2. As oscar, open O1 and the Statement: O1's line is Bumped, first on the GPU waitlist, with a
        // notification, and a Refund of 240.00, the full Charge.
        detail(as(oscar), o1[0])
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].line.state").value("WAITING"))
                .andExpect(jsonPath("$.lines[0].reason").value("BUMPED"))
                .andExpect(jsonPath("$.lines[0].reasonMessage").value(containsString("Bumped by a higher-tier line")))
                .andExpect(jsonPath("$.lines[0].waitlistPosition").value(1))
                .andExpect(jsonPath("$.history[?(@.line == " + o1[1] + " && @.toState == 'BUMPED')].note")
                        .value(hasItem(containsString("full Refund of 240.00 credits"))));
        assertThat(waitlistEntryOf(o1[1])).containsExactly("true/false");
        assertThat(noticesOf(world.oscar().id(), "BUMPED")).singleElement().asString()
                .contains("Line " + o1[1], "of Request " + o1[0], "Bumped by a higher-tier line",
                        "Full Refund of 240.00", "position 1 on the waitlist");
        statement(oscar, operations)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[?(@.kind == 'REFUND')].line").value((int) o1[1]))
                .andExpect(jsonPath("$.totals.refunds").value(240.00))
                .andExpect(jsonPath("$.account.balance").value(1000.00));
        assertThat(refundsOf(o1[1])).containsExactly("240.00");

        // Pass criteria: no hour exceeds the Pool Capacity, and the Bumped line is refunded in full.
        assertThat(meteredStates(o1[1])).containsExactly("BUMPED");
        assertThat(meteredStates(l1)).containsExactly("ALLOCATED");
        assertThat(peakLiveUnits(gpu, at(d, 10), at(d, 12))).isEqualTo(4).isLessThanOrEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                BigDecimal.class, l1)).isEqualByComparingTo("90.00");
        assertThat(account(operations)).isEqualTo(foldOfTransactions(operations));
    }
}
