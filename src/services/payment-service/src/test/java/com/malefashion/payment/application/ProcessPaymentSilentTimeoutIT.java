package com.malefashion.payment.application;

import com.malefashion.payment.application.command.ProcessPaymentCommand;
import com.malefashion.payment.domain.model.Payment;
import com.malefashion.payment.domain.model.PaymentStatus;
import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.support.ContractSchemas;
import com.malefashion.payment.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cùng nghiệp vụ process-payment nhưng chạy với {@code payment.mock.silent-on-timeout=true}.
 *
 * <p>Tách lớp vì property này được bind lúc context khởi động; cần một Spring context riêng để
 * chứng minh payment TIMEOUT vẫn được lưu nhưng không ghi outbox, kể cả khi command bị gửi lại.</p>
 */
@TestPropertySource(properties = {
        "payment.mock.honor-scenario=true",
        "payment.mock.silent-on-timeout=true"
})
class ProcessPaymentSilentTimeoutIT extends IntegrationTestBase {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private ProcessPaymentService processPaymentService;

    @Test
    void timeoutPersistsFailedPaymentWithoutOutboxEvent() {
        UUID orderId = UUID.randomUUID();

        processPaymentService.process(command(orderId, "TIMEOUT"), TRACE_ID);

        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(payment.getFailedAt()).isNotNull();
        assertThat(countPayments()).isEqualTo(1);
        assertThat(countOutboxEvents()).isZero();
    }

    @Test
    void replayedTimeoutCommandStillWritesNoOutboxEvent() {
        UUID orderId = UUID.randomUUID();

        processPaymentService.process(command(orderId, "TIMEOUT"), TRACE_ID);
        processPaymentService.process(command(orderId, "TIMEOUT"), TRACE_ID);

        assertThat(countPayments()).isEqualTo(1);
        assertThat(countOutboxEvents()).isZero();
    }

    @Test
    void successScenarioStillEmitsEventWhenSilentTimeoutIsOn() throws Exception {
        UUID orderId = UUID.randomUUID();

        processPaymentService.process(command(orderId, "SUCCESS"), TRACE_ID);

        List<String> payloads = storedOutboxPayloads();
        assertThat(payloads).hasSize(1);
        ContractSchemas.validateEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, payloads.getFirst());
    }

    private ProcessPaymentCommand command(UUID orderId, String scenario) {
        return new ProcessPaymentCommand(
                orderId, new BigDecimal("578000.00"), "VND", orderId.toString(), scenario);
    }
}
