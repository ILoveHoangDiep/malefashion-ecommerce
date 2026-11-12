package com.malefashion.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.malefashion.payment.infrastructure.messaging.MessageTypes;
import com.malefashion.payment.support.ContractSchemas;
import com.malefashion.payment.support.MessagingIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dùng nguyên ví dụ {@code examples[0]} của contract làm command thật để chứng minh service đọc đúng
 * định dạng order-service sẽ gửi.
 */
@TestPropertySource(properties = "payment.mock.honor-scenario=true")
class PaymentContractExamplesIT extends MessagingIntegrationTestBase {

    @Test
    void processPaymentExampleIsConsumedAndEmitsContractEvent() throws IOException {
        JsonNode command = firstExample("payment-process-payment.json");
        String json = objectMapper.writeValueAsString(command);
        ContractSchemas.validate("payment-process-payment.json", json);
        UUID commandEventId = UUID.fromString(command.path("eventId").asText());
        UUID orderId = UUID.fromString(command.path("payload").path("orderId").asText());

        sendRawCommand(MessageTypes.COMMAND_PROCESS_PAYMENT, commandEventId, json);

        Message message = awaitPublishedEvent(MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId);
        JsonNode event = envelopeOf(message);
        assertThat(event.path("eventId").asText()).isNotEqualTo(commandEventId.toString());
        assertThat(event.path("payload").path("amount").path("amount").asText())
                .isEqualTo("578000.00");
        assertContractInstant(event.path("occurredAt").asText());
        assertContractInstant(event.path("payload").path("paidAt").asText());
    }

    @Test
    void refundPaymentExampleAfterSuccessEmitsContractEvent() throws IOException {
        JsonNode processCommand = firstExample("payment-process-payment.json");
        String processJson = objectMapper.writeValueAsString(processCommand);
        UUID orderId = UUID.fromString(processCommand.path("payload").path("orderId").asText());
        sendRawCommand(MessageTypes.COMMAND_PROCESS_PAYMENT,
                UUID.fromString(processCommand.path("eventId").asText()), processJson);
        JsonNode succeeded = envelopeOf(awaitPublishedEvent(
                MessageTypes.EVENT_PAYMENT_SUCCEEDED, orderId));
        UUID paymentId = UUID.fromString(succeeded.path("payload").path("paymentId").asText());

        ObjectNode refundCommand = (ObjectNode) firstExample("payment-refund-payment.json");
        // Ví dụ tĩnh dùng paymentId minh hoạ; thay bằng paymentId thật vừa tạo cho đúng nghiệp vụ.
        ((ObjectNode) refundCommand.get("payload")).put("paymentId", paymentId.toString());
        refundCommand.put("eventId", UUID.randomUUID().toString());
        String refundJson = objectMapper.writeValueAsString(refundCommand);
        ContractSchemas.validate("payment-refund-payment.json", refundJson);

        sendRawCommand(MessageTypes.COMMAND_REFUND_PAYMENT,
                UUID.fromString(refundCommand.path("eventId").asText()), refundJson);

        Message message = awaitPublishedEvent(MessageTypes.EVENT_PAYMENT_REFUNDED, orderId);
        JsonNode refunded = envelopeOf(message);
        assertThat(refunded.path("payload").path("paymentId").asText())
                .isEqualTo(paymentId.toString());
        assertContractInstant(refunded.path("occurredAt").asText());
        assertContractInstant(refunded.path("payload").path("refundedAt").asText());
        assertThat(countRefunds()).isEqualTo(1);
    }

    private JsonNode firstExample(String schemaFile) throws IOException {
        Path file = ContractSchemas.eventsDirectory().resolve(schemaFile);
        JsonNode schema = objectMapper.readTree(Files.readString(file));
        JsonNode examples = schema.path("examples");
        assertThat(examples.isArray()).as("examples[] in %s", schemaFile).isTrue();
        assertThat(examples).isNotEmpty();
        return examples.get(0);
    }
}
