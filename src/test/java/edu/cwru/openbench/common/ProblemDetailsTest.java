package edu.cwru.openbench.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cwru.openbench.PostgresTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Every error class of design Table "HTTP status per error class" as the client sees it. */
@Import(ProblemDetailsTest.Refusals.class)
class ProblemDetailsTest extends PostgresTest {

    private static final RequestPostProcessor MEMBER = user("member").authorities(() -> "MEMBER");

    @ParameterizedTest
    @CsvSource({
        "WRONG_STATE, 409",
        "ALREADY_DECIDED, 409",
        "ALLOCATION_CONFLICT, 409",
        "INSUFFICIENT_BALANCE, 422",
        "TYPE_RETIRED, 422"})
    void mapsARuleViolationTo409Or422ByItsCode(String code, int status) throws Exception {
        perform(get("/api/test/rule/" + code))
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").value("The Required lines need 1840.00 credits."))
                .andExpect(jsonPath("$.instance").value("/api/test/rule/" + code))
                .andExpect(jsonPath("$.type").value("/problems/" + code.toLowerCase().replace('_', '-')))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.shortfall").value(640.00));
    }

    @Test
    void mapsNotFoundAndForbidden() throws Exception {
        perform(get("/api/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        perform(get("/api/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("Only the borrowing Manager may end this Loan."));
        perform(get("/api/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void mapsAnExclusionViolationTo409AllocationConflict() throws Exception {
        perform(get("/api/test/exclusion-violation"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_CONFLICT"));
    }

    @Test
    void mapsAConcurrencyRetryTo503WithRetryAfter() throws Exception {
        perform(get("/api/test/concurrency"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("CONCURRENCY_RETRY"));
    }

    @Test
    void mapsAnythingElseTo500WithoutItsDetails() throws Exception {
        perform(get("/api/test/bug"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(content().string(not(containsString("secret internals"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void listsEveryInvalidFieldOnA400() throws Exception {
        perform(post("/api/test/validated").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \" \", \"quantity\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].message").value("must not be blank"))
                .andExpect(jsonPath("$.errors[?(@.field == 'quantity')].message").value("must be greater than 0"));
        perform(post("/api/test/validated").with(xsrf()).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void convertsATypedIdPathVariable() throws Exception {
        perform(get("/api/test/teams/42"))
                .andExpect(status().isOk())
                .andExpect(content().string("42"));
        perform(get("/api/test/teams/forty-two"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(MEMBER));
    }

    @RestController
    static class Refusals {

        @GetMapping("/api/test/rule/{code}")
        void rule(@PathVariable String code) {
            throw new RuleViolation(code, "The Required lines need 1840.00 credits.",
                    Map.of("shortfall", Credits.of("640.00")));
        }

        @GetMapping("/api/test/not-found")
        void notFound() {
            throw new NotFoundException();
        }

        @GetMapping("/api/test/forbidden")
        void forbidden() {
            throw new ForbiddenException("Only the borrowing Manager may end this Loan.");
        }

        @GetMapping("/api/test/exclusion-violation")
        void exclusionViolation() {
            throw new DataIntegrityViolationException("exclusive_occurrence_no_overlap",
                    new SQLException("conflicting key value violates exclusion constraint", "23P01"));
        }

        @GetMapping("/api/test/concurrency")
        void concurrency() {
            throw new ConcurrencyRetryException("Deadlock twice", null);
        }

        @GetMapping("/api/test/bug")
        void bug() {
            throw new IllegalStateException("secret internals");
        }

        @PostMapping("/api/test/validated")
        void validated(@Valid @RequestBody Draft draft) {}

        @GetMapping("/api/test/teams/{id}")
        TeamId team(@PathVariable TeamId id) {
            return id;
        }

        record Draft(@NotBlank String name, @Positive int quantity) {}
    }
}
