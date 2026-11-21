package com.malefashion.order.integration;

import com.malefashion.order.infrastructure.outbox.OutboxPublisher;
import com.malefashion.order.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test chỉ có PostgreSQL phải cô lập broker: listener không start và publisher không tồn tại, dù
 * cấu hình mặc định của ứng dụng là bật cả hai.
 */
class OrderMessagingIsolationIT extends IntegrationTestBase {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Test
    void listenerAndPublisherAreOffByDefaultInPostgresOnlyTests() {
        assertThat(environment.getProperty("order.messaging.listener.auto-startup",
                Boolean.class)).isFalse();
        assertThat(environment.getProperty("order.outbox.publisher.enabled",
                Boolean.class)).isFalse();
        assertThat(applicationContext.getBeanNamesForType(OutboxPublisher.class)).isEmpty();

        assertThat(listenerRegistry.getListenerContainers())
                .noneMatch(container -> container.isRunning());
    }
}
