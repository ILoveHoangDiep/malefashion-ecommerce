package com.malefashion.order.config;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

/**
 * Chỉ cấu hình connection/listener; exchange, queue và binding thuộc
 * {@code deploy/rabbitmq/definitions.json}, service không tự khai báo.
 *
 * <p>Hai nhóm lỗi được phân biệt rõ:</p>
 * <ul>
 *   <li>vĩnh viễn (JSON hỏng, thiếu field, lệch header/body, type không hỗ trợ): parser ném
 *       {@link AmqpRejectAndDontRequeueException}, retry policy bỏ qua ngay và recoverer đưa vào DLQ;</li>
 *   <li>tạm thời (optimistic lock, lỗi DB, exception chưa phân loại): retry đủ số lần rồi mới DLQ.</li>
 * </ul>
 */
@Configuration
public class RabbitConfig {

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            OrderProperties properties) {

        OrderProperties.Messaging.Retry retry = properties.messaging().retry();
        OrderProperties.Messaging.Listener settings = properties.messaging().listener();

        // defaultValue=true rất quan trọng: constructor dùng map mặc định coi exception không được
        // liệt kê là "không retry", khiến lỗi hạ tầng vào DLQ ngay sau lần đầu.
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                retry.maxAttempts(),
                Map.of(AmqpRejectAndDontRequeueException.class, false),
                true,
                true);

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setChannelTransacted(false);
        factory.setDefaultRequeueRejected(false);
        factory.setAutoStartup(settings.autoStartup());
        factory.setConcurrentConsumers(settings.concurrency());
        factory.setMaxConcurrentConsumers(settings.concurrency());
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .retryPolicy(retryPolicy)
                        .backOffOptions(retry.initialIntervalMs(), retry.multiplier(),
                                retry.maxIntervalMs())
                        .recoverer(new RejectAndDontRequeueRecoverer())
                        .build());
        return factory;
    }
}
