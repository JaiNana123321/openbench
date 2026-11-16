package edu.cwru.openbench.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.Verifies;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The demand surcharge test case of {@code docs/test-cases/sections/cases/10-ledger.tex} (SRS
 * Ledger.Surcharge), step by step through the API.
 */
class SurchargeCasesTest extends TierWorldTest {

    @Test
    @DisplayName("led-surcharge: L1 quotes 60.00 in an empty window, 90.00 with the pool half full, and its locked Quote and Charge stay 90.00 after O1's Release")
    @Verifies("led-surcharge")
    void raisesTheQuoteWithTheFillAndLocksIt() throws Exception {
        LocalDate d = dayD();
        long erinId = jdbc.queryForObject("SELECT id FROM member WHERE username = ?", Long.class, erin);
        String key = "surcharge." + gpu;

        // 1. Enable the demand surcharge for GPU (rate 1.0): saved and recorded.
        setSetting(key, "1.0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricing['" + gpu + "'].surchargeRate").value(1.0));
        mvc.perform(get("/api/settings/changes").with(as(erin)).param("size", "200"))
                .andExpect(jsonPath("$.content[?(@.key == '" + key + "')].oldValue").value(hasItem("off")))
                .andExpect(jsonPath("$.content[?(@.key == '" + key + "')].newValue").value(hasItem("1")))
                .andExpect(jsonPath("$.content[?(@.key == '" + key + "')].actor").value(hasItem((int) erinId)));

        // 2. As alice, add L1 (GPU, 4 units, D 10:00-12:00) to a Draft: 60.00, nothing is held or allocated.
        long a = createDraft(as(alice));
        long l1 = gpuLine(as(alice), a, 4, at(d, 10), at(d, 12), null);
        draft(a)
                .andExpect(jsonPath("$.lines[0].quote").value(60.00))
                .andExpect(jsonPath("$.lines[0].surcharge").doesNotExist());

        // 3. As oscar, submit O1 (GPU, 8 units, D 10:00-12:00); as alice, reopen the Draft: half the pool
        // is taken, so L1 quotes 60.00 x (1 + 1.0 x 0.5) = 90.00.
        long[] o1 = submittedGpu(oscar, 8, at(d, 10), at(d, 12), null);
        detail(as(oscar), o1[0]).andExpect(jsonPath("$.request.state").value("ALLOCATED"));
        draft(a)
                .andExpect(jsonPath("$.lines[0].quote").value(90.00))
                .andExpect(jsonPath("$.lines[0].surcharge").value(1.5))
                .andExpect(jsonPath("$.requiredSum").value(90.00));

        // 4. As alice, submit; then oscar Releases O1: L1's locked Quote and its Charge stay 90.00.
        nextSecond();
        submit(as(alice), a).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ALLOCATED"));
        detail(as(alice), a).andExpect(jsonPath("$.lines[0].line.quote").value(90.00));
        mvc.perform(post("/api/requests/{id}/lines/{line}/release", o1[0], o1[1]).with(as(oscar))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"WHOLE\"}"))
                .andExpect(status().isOk());
        detail(as(alice), a)
                .andExpect(jsonPath("$.lines[0].line.quote").value(90.00))
                .andExpect(jsonPath("$.lines[0].line.surcharge").doesNotExist());
        draft(a).andExpect(jsonPath("$.lines[0].quote").value(90.00));

        // Pass criteria: the surcharge depends on the share taken at quote time and is fixed once locked.
        assertThat(jdbc.queryForObject("SELECT locked_quote FROM request_line WHERE id = ?", BigDecimal.class, l1))
                .isEqualByComparingTo("90.00");
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE kind = 'CHARGE' AND line_id = ?",
                BigDecimal.class, l1)).isEqualByComparingTo("90.00");
        assertThat(account(platform)).isEqualTo(foldOfTransactions(platform));
    }

    private ResultActions draft(long request) throws Exception {
        return mvc.perform(get("/api/requests/{id}/draft", request).with(as(alice))).andExpect(status().isOk());
    }
}
