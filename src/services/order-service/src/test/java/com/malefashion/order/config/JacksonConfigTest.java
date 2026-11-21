package com.malefashion.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    private final ObjectMapper objectMapper = mapper();

    @Test
    void serializesInstantWithExactlyThreeMillisAndZ() throws Exception {
        String json = objectMapper.writeValueAsString(new Sample(
                Instant.parse("2026-10-22T03:15:30.100Z"), "value"));

        assertThat(json).contains("\"at\":\"2026-10-22T03:15:30.100Z\"");
        assertThat(json).doesNotContain("+00:00");
    }

    @Test
    void omitsNullFields() throws Exception {
        String json = objectMapper.writeValueAsString(new Sample(
                Instant.parse("2026-10-22T03:15:30.123Z"), null));

        assertThat(json).doesNotContain("value");
    }

    @Test
    void ignoresUnknownFieldsAndParsesOffsetDateTime() throws Exception {
        Sample sample = objectMapper.readValue("""
                {"at":"2026-10-22T03:15:30.123+00:00","value":"x","unknown":1}
                """, Sample.class);

        assertThat(sample.at()).isEqualTo(Instant.parse("2026-10-22T03:15:30.123Z"));
        assertThat(sample.value()).isEqualTo("x");
    }

    @Test
    void acceptsContractTimestamp() throws Exception {
        Sample sample = objectMapper.readValue("""
                {"at":"2026-10-22T03:15:30.123Z"}
                """, Sample.class);

        assertThat(sample.at()).isEqualTo(Instant.parse("2026-10-22T03:15:30.123Z"));
    }

    private static ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().instantContractCustomizer().customize(builder);
        return builder.build();
    }

    private record Sample(Instant at, String value) {
    }
}
