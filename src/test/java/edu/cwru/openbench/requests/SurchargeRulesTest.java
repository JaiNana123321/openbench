package edu.cwru.openbench.requests;

import static edu.cwru.openbench.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The rules of the demand surcharge (issue #25; SRS Ledger.Surcharge) beyond the test case: the
 * setting's refusals, a surcharge that is off, Exclusive fill, the premiums it multiplies with, and
 * a locked Quote that later fills do not move.
 */
class SurchargeRulesTest extends TierWorldTest {

    @Test
    @DisplayName("Setting: a negative rate is VALIDATION_FAILED, a Person type CLASS_MISMATCH, an unpriced type UNIT_PRICE_REQUIRED; 0 turns it off")
    void refusesInvalidRates() throws Exception {
        String key = "surcharge." + gpu;
        setSetting(key, "-0.5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        setSetting("surcharge." + person, "1.0")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLASS_MISMATCH"));
        long unpriced = testData().resourceType(unique("Storage"), "METERED");
        setSetting("surcharge." + unpriced, "1.0")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNIT_PRICE_REQUIRED"));
        setSetting(key, "\"high\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM setting_change WHERE key = ?", Integer.class, key)).isZero();

        setSetting(key, "0.5").andExpect(status().isOk()).andExpect(jsonPath("$.pricing['" + gpu + "'].surchargeRate").value(0.5));
        setSetting(key, "0").andExpect(status().isOk()).andExpect(jsonPath("$.pricing['" + gpu + "'].surchargeRate").value(0));
        assertThat(jdbc.queryForList("SELECT old_value || '>' || new_value FROM setting_change WHERE key = ? ORDER BY id",
                String.class, key)).containsExactly("off>0.5", "0.5>off");
    }

    @Test
    @DisplayName("Off: with the surcharge off, a full pool still quotes at base")
    void offQuotesAtBase() throws Exception {
        LocalDate d = dayD();
        submittedGpu(oscar, 16, at(d, 10), at(d, 12), null);
        long a = createDraft(as(alice));
        gpuLine(as(alice), a, 4, at(d, 10), at(d, 12), null);
        draft(a).andExpect(jsonPath("$.lines[0].quote").value(60.00)).andExpect(jsonPath("$.lines[0].surcharge").doesNotExist());
    }

    @Test
    @DisplayName("Fill: the peak hour counts, held units count, and a type with no capacity has no surcharge")
    void usesThePeakHour() throws Exception {
        LocalDate d = dayD();
        setSetting("surcharge." + gpu, "1.0").andExpect(status().isOk());
        // 4 units at 10:00-11:00 and 12 at 11:00-12:00: the peak hour is 12 of 16, a multiplier of 1.75.
        submittedGpu(oscar, 4, at(d, 10), at(d, 11), null);
        submittedGpu(oscar, 12, at(d, 11), at(d, 12), null);
        long a = createDraft(as(alice));
        gpuLine(as(alice), a, 4, at(d, 10), at(d, 12), null);
        draft(a).andExpect(jsonPath("$.lines[0].quote").value(105.00)).andExpect(jsonPath("$.lines[0].surcharge").value(1.75));

        long empty = testData().resourceType(unique("Empty pool"), "METERED");
        jdbc.update("INSERT INTO type_pricing (type_id, unit_price, surcharge_rate) VALUES (?, 5.00, 1)", empty);
        long b = createDraft(as(alice));
        mvc.perform(post("/api/requests/{id}/lines", b).with(as(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeId\": " + empty + ", \"quantity\": 2, \"start\": \"" + at(d, 10) + "\", \"end\": \""
                                + at(d, 12) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quote").value(20.00));
    }

    @Test
    @DisplayName("Exclusive and premiums: one of two rooms taken is half full; the surcharge multiplies with the bands and the Priority premium, and with a tier premium")
    void multipliesWithBandsAndPremiums() throws Exception {
        LocalDate d = dayD();
        setSetting("surcharge." + room, "1.0").andExpect(status().isOk());
        setSetting("surcharge." + gpu, "1.0").andExpect(status().isOk());
        submitted(bob, room, at(d, 10), at(d, 12), roomA, false);
        long[] tiers = standardAndExpress();
        submittedGpu(oscar, 8, at(d, 10), at(d, 12), null);

        long a = createDraft(as(alice));
        long room1 = line(as(alice), a, room, 1, at(d, 10), at(d, 12), null);
        setPriority(as(alice), a, room1, true).andExpect(status().isOk())
                // 2 hours x 10.00 x 1.5 (band) x 2 (Priority) x 1.5 (half the rooms taken) = 90.00.
                .andExpect(jsonPath("$.quote").value(90.00))
                .andExpect(jsonPath("$.surcharge").value(1.5));
        // 4 units x 2 hours x 5.00 x 1.5 (band) x 1.5 (Express) x 1.5 (half the pool taken) = 135.00.
        addTieredLine(as(alice), a, gpu, 4, at(d, 10), at(d, 12), tiers[1])
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quote").value(135.00));
    }

    @Test
    @DisplayName("Locked: a later fill does not move a locked Quote, and the Charge equals it")
    void laterFillsDoNotMoveALockedQuote() throws Exception {
        LocalDate d = dayD();
        setSetting("surcharge." + gpu, "1.0").andExpect(status().isOk());
        long[] first = submittedGpu(alice, 4, at(d, 10), at(d, 12), null);
        submittedGpu(oscar, 12, at(d, 10), at(d, 12), null);
        detail(as(alice), first[0]).andExpect(jsonPath("$.lines[0].line.quote").value(60.00));
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                BigDecimal.class, first[1])).isEqualByComparingTo("60.00");
    }

    private ResultActions draft(long request) throws Exception {
        return mvc.perform(get("/api/requests/{id}/draft", request).with(as(alice))).andExpect(status().isOk());
    }
}
