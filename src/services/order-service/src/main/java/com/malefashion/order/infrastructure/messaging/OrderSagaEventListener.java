package com.malefashion.order.infrastructure.messaging;

import com.malefashion.order.application.InboxProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Nhận 8 event saga từ queue do definitions.json tạo. Listener cố tình không có
 * {@code @Transactional}: {@link InboxProcessor} mở transaction bao gồm dedupe + nghiệp vụ.
 *
 * <p>MDC chỉ chứa traceId/orderId/eventId, không chứa email hay payload, và luôn được dọn trong
 * {@code finally} kể cả khi handler ném exception.</p>
 */
@Component
public class OrderSagaEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaEventListener.class);

    private final InboundEventParser inboundEventParser;
    private final InboxProcessor inboxProcessor;

    public OrderSagaEventListener(InboundEventParser inboundEventParser,
                                  InboxProcessor inboxProcessor) {
        this.inboundEventParser = inboundEventParser;
        this.inboxProcessor = inboxProcessor;
    }

    @RabbitListener(
            queues = "${order.messaging.listener.queue:order-service.saga.events}",
            autoStartup = "${order.messaging.listener.auto-startup:true}")
    public void onEvent(Message message) {
        InboundEvent event = inboundEventParser.parse(message);

        MDC.put(TraceIds.MDC_TRACE_ID, event.traceId());
        MDC.put(TraceIds.MDC_ORDER_ID, event.orderId().toString());
        MDC.put(TraceIds.MDC_EVENT_ID, event.eventId().toString());
        try {
            log.debug("Processing event orderId={} eventId={} eventType={}",
                    event.orderId(), event.eventId(), event.eventType());
            inboxProcessor.process(event);
        } finally {
            MDC.remove(TraceIds.MDC_TRACE_ID);
            MDC.remove(TraceIds.MDC_ORDER_ID);
            MDC.remove(TraceIds.MDC_EVENT_ID);
        }
    }
}
