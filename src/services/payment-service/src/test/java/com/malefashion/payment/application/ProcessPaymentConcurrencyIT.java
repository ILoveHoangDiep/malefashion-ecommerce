package com.malefashion.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.malefashion.payment.application.command.ProcessPaymentCommand;
import com.malefashion.payment.domain.model.Payment;
import com.malefashion.payment.domain.model.PaymentStatus;
import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.support.ContractSchemas;
import com.malefashion.payment.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20 thread cùng chạy process-payment cho một orderId: unique constraint + ON CONFLICT phải bảo đảm
 * đúng một payment, còn các lệnh thua phát lại event của chính payment đó.
 */
class ProcessPaymentConcurrencyIT extends IntegrationTestBase {

    private static final int WORKERS = 20;
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private ProcessPaymentService processPaymentService;

    @Test
    void twentyConcurrentCommandsCreateOnePaymentAndConsistentEvents() throws Exception {
        UUID orderId = UUID.randomUUID();
        ProcessPaymentCommand command = new ProcessPaymentCommand(
                orderId, new BigDecimal("578000.00"), "VND", orderId.toString(), "SUCCESS");

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < WORKERS; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate timed out");
                    }
                    processPaymentService.process(command, TRACE_ID);
                    return null;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(countPayments()).isEqualTo(1);
        Payment payment = paymentRepository.findAll().getFirst();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderTransactionId()).matches("^MOCK-TXN-\\d{8}-\\d{6,}$");

        List<String> payloads = storedOutboxPayloads();
        assertThat(payloads).hasSize(WORKERS);

        Set<String> eventIds = new HashSet<>();
        for (String json : payloads) {
            ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, json);
            JsonNode event = objectMapper.readTree(json);
            assertThat(event.path("eventType").asText()).isEqualTo("payment.succeeded");
            assertThat(event.path("payload").path("paymentId").asText())
                    .isEqualTo(payment.getId().toString());
            assertThat(event.path("payload").path("providerTransactionId").asText())
                    .isEqualTo(payment.getProviderTransactionId());
            assertThat(event.path("payload").path("amount").path("amount").asText())
                    .isEqualTo("578000.00");
            eventIds.add(event.path("eventId").asText());
        }
        assertThat(eventIds).hasSize(WORKERS);
    }
}
