package com.malefashion.inventory;

import com.malefashion.inventory.application.ReserveStockService;
import com.malefashion.inventory.config.InventoryProperties;
import com.malefashion.inventory.config.RabbitConfig;
import com.malefashion.inventory.infrastructure.messaging.InventoryCommandListener;
import com.malefashion.inventory.infrastructure.messaging.envelope.MessageEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Guards the rule that the message consumption path must not be transactional: the reserve retry loop
 * has to open a fresh transaction per attempt, so neither the listener nor the retry service may carry
 * {@code @Transactional}, and the listener container must not run in channel-transacted mode.
 */
class ListenerPathTransactionalityTest {

    @Test
    void listenerAndReserveRetryLoopAreNotTransactional() throws NoSuchMethodException {
        assertThat(AnnotatedElementUtils.findMergedAnnotation(
                InventoryCommandListener.class, Transactional.class)).isNull();
        assertThat(AnnotatedElementUtils.findMergedAnnotation(
                InventoryCommandListener.class.getMethod("onCommand", Message.class),
                Transactional.class)).isNull();
        assertThat(AnnotatedElementUtils.findMergedAnnotation(
                ReserveStockService.class, Transactional.class)).isNull();
        assertThat(AnnotatedElementUtils.findMergedAnnotation(
                ReserveStockService.class.getMethod("handle", MessageEnvelope.class),
                Transactional.class)).isNull();
    }

    @Test
    void listenerContainerDoesNotUseChannelTransacted() {
        RabbitConfig rabbitConfig = new RabbitConfig();
        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setMessageListener(message -> {
        });
        SimpleMessageListenerContainer container = rabbitConfig
                .rabbitListenerContainerFactory(mock(ConnectionFactory.class), properties())
                .createListenerContainer(endpoint);

        assertThat(container.isChannelTransacted()).isFalse();
    }

    private InventoryProperties properties() {
        return new InventoryProperties(
                new InventoryProperties.Reservation(
                        Duration.ofMinutes(15), 60_000L, 60_000L, 100, 5, 25L, 25L),
                new InventoryProperties.Outbox(100, 500L, 1000L, 5000L),
                new InventoryProperties.Messaging("inventory-service",
                        new InventoryProperties.Retry(3, 500L, 2.0, 5000L)));
    }
}
