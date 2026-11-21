package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.application.OutboxService;
import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.messaging.MessageEnvelope;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.infrastructure.persistence.OutboxEventRepository;
import com.malefashion.order.support.SagaHandlerTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C.11: outbox lỗi phải rollback cả thay đổi order và marker inbox, không để lại trạng thái nửa vời.
 */
@Import(SagaHandlerAtomicityIT.FailingOutboxConfig.class)
class SagaHandlerAtomicityIT extends SagaHandlerTestBase {

    @Test
    void commandFailureRollsBackOrderAndInboxMarker() {
        save(newOrder());
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> deliver(inbound("inventory-stock-reserved.json", eventId)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_RESERVATION);
        assertThat(order.getReservationId()).isNull();
        assertThat(markerCount(eventId)).isZero();
        assertNoOutbox();
    }

    @Test
    void eventFailureRollsBackCancellationAndInboxMarker() {
        save(newOrder());
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() ->
                deliver(inbound("inventory-stock-reservation-failed.json", eventId)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        Order order = reload();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getCancelReason()).isNull();
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_RESERVATION);
        assertThat(markerCount(eventId)).isZero();
        assertNoOutbox();
    }

    @TestConfiguration
    static class FailingOutboxConfig {

        @Bean
        @Primary
        OutboxService failingOutboxService(OutboxEventRepository repository,
                                           ObjectMapper objectMapper,
                                           Clock clock) {
            return new FailingOutboxService(repository, objectMapper, clock);
        }
    }

    static class FailingOutboxService extends OutboxService {

        FailingOutboxService(OutboxEventRepository repository, ObjectMapper objectMapper,
                             Clock clock) {
            super(repository, objectMapper, clock);
        }

        @Override
        public OutboxEvent appendCommand(MessageEnvelope<?> envelope) {
            throw new DataAccessResourceFailureException("simulated outbox outage");
        }

        @Override
        public OutboxEvent appendEvent(MessageEnvelope<?> envelope) {
            throw new DataAccessResourceFailureException("simulated outbox outage");
        }
    }
}
