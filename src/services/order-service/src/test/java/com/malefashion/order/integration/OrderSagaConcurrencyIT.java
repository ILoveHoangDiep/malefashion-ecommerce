package com.malefashion.order.integration;

import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.SagaStep;
import com.malefashion.order.infrastructure.messaging.InboundEvent;
import com.malefashion.order.infrastructure.messaging.MessageTypes;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.SagaHandlerTestBase;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C.12: hai bản payment.succeeded khác eventId tới đồng thời. Khóa ghi trên dòng order bảo đảm chỉ
 * một bản chuyển sang AWAITING_COMMIT; bản còn lại thấy state đã đổi nên bị bỏ qua.
 */
class OrderSagaConcurrencyIT extends SagaHandlerTestBase {

    @Test
    void concurrentPaymentSucceededSendsExactlyOneCommitCommand() throws Exception {
        save(awaitingPaymentOrder());
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        InboundEvent first = inbound("payment-succeeded.json", firstEventId);
        InboundEvent second = inbound("payment-succeeded.json", secondEventId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstResult = executor.submit(() -> deliverTogether(first, ready, start));
            Future<?> secondResult = executor.submit(() -> deliverTogether(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstResult.get(30, TimeUnit.SECONDS);
            secondResult.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Order order = reload();
        assertThat(order.getSagaStep()).isEqualTo(SagaStep.AWAITING_COMMIT);
        assertThat(order.getCommitRetryCount()).isZero();
        assertThat(order.getCommitCommandEventId()).isNotNull();
        assertThat(outboxEvents(MessageTypes.COMMAND_COMMIT_STOCK)).hasSize(1);
        assertThat(outboxEvents()).hasSize(1);
        assertThat(markerCount(firstEventId)).isEqualTo(1);
        assertThat(markerCount(secondEventId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE message_id IN (?, ?)", Long.class,
                firstEventId, secondEventId)).isEqualTo(2);
        assertThat(order.getPaymentId()).isEqualTo(OrderFixtures.PAYMENT_ID);
    }

    private void deliverTogether(InboundEvent event, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        deliver(event);
    }
}
