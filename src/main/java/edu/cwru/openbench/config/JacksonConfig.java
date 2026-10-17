package edu.cwru.openbench.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The JSON representation of design Section 7: instants as ISO-8601 date-times with the company
 * offset ({@code 2026-10-12T09:00:00-04:00}), accepted with any offset; dates as ISO dates. Typed
 * ids and {@code Credits} carry their own Jackson annotations, so they are numbers in any mapper.
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer openBenchJson(Clock clock) {
        return customizer(clock.getZone());
    }

    static Jackson2ObjectMapperBuilderCustomizer customizer(ZoneId zone) {
        return builder -> builder
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializerByType(Instant.class, new CompanyZoneInstantSerializer(zone));
    }

    /** Writes an instant at the company zone's offset; Jackson's default reads any offset. */
    static final class CompanyZoneInstantSerializer extends StdSerializer<Instant> {

        private final ZoneId zone;

        CompanyZoneInstantSerializer(ZoneId zone) {
            super(Instant.class);
            this.zone = zone;
        }

        @Override
        public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atZone(zone)));
        }
    }
}
