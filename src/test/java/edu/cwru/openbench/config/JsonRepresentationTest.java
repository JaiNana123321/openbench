package edu.cwru.openbench.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cwru.openbench.common.DateRange;
import edu.cwru.openbench.common.ResourceTypeId;
import edu.cwru.openbench.common.TeamId;
import edu.cwru.openbench.common.TimeWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** The JSON representation of design Section 7, with the customizer the application installs. */
class JsonRepresentationTest {

    private final ObjectMapper json = mapper();

    private static ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        JacksonConfig.customizer(ZoneId.of("America/New_York")).customize(builder);
        return builder.build();
    }

    @Test
    void writesInstantsAtTheCompanyOffset() throws Exception {
        TimeWindow window = new TimeWindow(Instant.parse("2026-10-27T18:00:00Z"), Instant.parse("2026-10-27T20:00:00Z"));
        assertThat(json.writeValueAsString(window))
                .isEqualTo("{\"start\":\"2026-10-27T14:00:00-04:00\",\"end\":\"2026-10-27T16:00:00-04:00\"}");
        // After the change to standard time the offset follows the zone.
        assertThat(json.writeValueAsString(Instant.parse("2026-11-02T14:00:00Z"))).isEqualTo("\"2026-11-02T09:00:00-05:00\"");
    }

    @Test
    void readsInstantsWithAnyOffset() throws Exception {
        Instant expected = Instant.parse("2026-10-27T18:00:00Z");
        assertThat(json.readValue("\"2026-10-27T14:00:00-04:00\"", Instant.class)).isEqualTo(expected);
        assertThat(json.readValue("\"2026-10-27T18:00:00Z\"", Instant.class)).isEqualTo(expected);
        assertThat(json.readValue("\"2026-10-27T19:00:00+01:00\"", Instant.class)).isEqualTo(expected);
    }

    @Test
    void writesDayRangesAsIsoDates() throws Exception {
        DateRange days = new DateRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6));
        assertThat(json.writeValueAsString(days)).isEqualTo("{\"from\":\"2026-11-02\",\"to\":\"2026-11-06\"}");
        assertThat(json.readValue("{\"from\":\"2026-11-02\",\"to\":\"2026-11-06\"}", DateRange.class)).isEqualTo(days);
    }

    @Test
    void refusesAWindowThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> json.readValue(
                        "{\"start\":\"2026-10-27T16:00:00-04:00\",\"end\":\"2026-10-27T14:00:00-04:00\"}", TimeWindow.class))
                .isInstanceOf(JsonMappingException.class);
    }

    @Test
    void convertsPathVariablesToTypedIds() {
        var factory = new WebConfig.StringToTypedIdConverterFactory();
        assertThat(factory.getConverter(TeamId.class).convert("42")).isEqualTo(new TeamId(42));
        assertThat(factory.getConverter(ResourceTypeId.class).convert(" 7 ")).isEqualTo(new ResourceTypeId(7));
    }
}
