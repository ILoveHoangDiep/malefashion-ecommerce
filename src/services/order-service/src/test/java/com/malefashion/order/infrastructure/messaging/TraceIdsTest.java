package com.malefashion.order.infrastructure.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIdsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesThirtyTwoLowercaseHexCharacters() {
        String traceId = TraceIds.newTraceId();

        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat(TraceIds.isValid(traceId)).isTrue();
    }

    @Test
    void reusesValidTraceIdFromMdc() {
        MDC.put(TraceIds.MDC_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");

        assertThat(TraceIds.currentOrNew()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void replacesInvalidTraceIdFromMdc() {
        MDC.put(TraceIds.MDC_TRACE_ID, "NOT-A-TRACE-ID");

        String traceId = TraceIds.currentOrNew();

        assertThat(traceId).matches("^[0-9a-f]{32}$");
        assertThat(traceId).isNotEqualTo("NOT-A-TRACE-ID");
    }

    @Test
    void buildsW3cTraceparentAtPublishTime() {
        String traceparent = TraceIds.traceparent("4bf92f3577b34da6a3ce929d0e0e4736");

        assertThat(traceparent)
                .matches("^00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01$");
        assertThat(TraceIds.traceparent("4bf92f3577b34da6a3ce929d0e0e4736"))
                .isNotEqualTo(traceparent);
    }

    @Test
    void rejectsInvalidTraceIdWhenBuildingTraceparent() {
        assertThatThrownBy(() -> TraceIds.traceparent("ABC"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
