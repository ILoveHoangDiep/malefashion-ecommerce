package com.malefashion.payment;

import com.malefashion.payment.infrastructure.outbox.OutboxPublisher;
import com.malefashion.payment.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test chỉ có PostgreSQL không được mở listener RabbitMQ hay chạy scheduler outbox, để không consume
 * command và không publish event vào broker thật (kể cả khi máy dev đang chạy RabbitMQ).
 */
class PaymentTestIsolationIT extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Test
    void listenerAndOutboxPublisherAreDisabledForDatabaseOnlyTests() {
        assertThat(environment.getProperty("payment.messaging.listener.auto-startup"))
                .isEqualTo("false");
        assertThat(environment.getProperty("payment.outbox.publisher.enabled"))
                .isEqualTo("false");

        assertThat(applicationContext.getBeansOfType(OutboxPublisher.class)).isEmpty();

        assertThat(listenerRegistry.getListenerContainers())
                .as("PaymentCommandListener vẫn được cấu hình nhưng không được start")
                .isNotEmpty()
                .allMatch(container -> !container.isRunning());
    }
}
