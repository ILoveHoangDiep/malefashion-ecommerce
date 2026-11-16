package com.malefashion.inventory.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Contract timestamps are ISO-8601 UTC with exactly three fractional digits and a trailing {@code Z}.
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter CONTRACT_INSTANT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneOffset.UTC);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer instantContractCustomizer() {
        return builder -> {
            builder.serializerByType(Instant.class, new ContractInstantSerializer());
            builder.deserializerByType(Instant.class, new FlexibleInstantDeserializer());
        };
    }

    static final class ContractInstantSerializer extends JsonSerializer<Instant> {

        @Override
        public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeString(CONTRACT_INSTANT_FORMATTER.format(value));
        }
    }

    static final class FlexibleInstantDeserializer extends JsonDeserializer<Instant> {

        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getText();
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException firstAttempt) {
                try {
                    return OffsetDateTime.parse(text).toInstant();
                } catch (DateTimeParseException secondAttempt) {
                    return (Instant) context.handleWeirdStringValue(
                            Instant.class, text, "Expected an ISO-8601 instant");
                }
            }
        }
    }
}
