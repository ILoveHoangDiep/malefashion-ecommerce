package com.malefashion.order.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.application.OutboxService;
import com.malefashion.order.infrastructure.messaging.MessageEnvelope;
import com.malefashion.order.infrastructure.persistence.OutboxEvent;
import com.malefashion.order.infrastructure.persistence.OutboxEventRepository;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.UpstreamFixtures;
import com.malefashion.order.support.WebIntegrationTestBase;
import com.malefashion.order.support.WireMockUpstream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.7: outbox lỗi giữa transaction → rollback cả order, order_items, idempotency và outbox.
 */
@Import(CheckoutAtomicityIT.FailingOutboxConfig.class)
class CheckoutAtomicityIT extends WebIntegrationTestBase {

    @Test
    void outboxFailureRollsBackEverything() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();

        ResponseEntity<String> response = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(errorCode(response)).isEqualTo("INTERNAL_ERROR");
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
        assertThat(count("checkout_idempotency")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
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
        public OutboxEvent appendEvent(MessageEnvelope<?> envelope) {
            throw new DataAccessResourceFailureException("simulated outbox outage");
        }

        @Override
        public OutboxEvent appendCommand(MessageEnvelope<?> envelope) {
            throw new DataAccessResourceFailureException("simulated outbox outage");
        }
    }
}
