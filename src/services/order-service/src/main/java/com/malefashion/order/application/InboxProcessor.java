package com.malefashion.order.application;

import com.malefashion.order.config.OrderProperties;
import com.malefashion.order.domain.support.Instants;
import com.malefashion.order.infrastructure.messaging.InboundEvent;
import com.malefashion.order.infrastructure.persistence.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Inbox + handler trong MỘT transaction: marker dedupe, thay đổi order và outbox event cùng commit
 * hoặc cùng rollback. Exception từ handler không bắt lại nên retry policy của listener xử lý.
 */
@Service
public class InboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);

    private final ProcessedMessageRepository processedMessageRepository;
    private final SagaEventHandler sagaEventHandler;
    private final TransactionTemplate transactionTemplate;
    private final String consumerName;
    private final Clock clock;

    public InboxProcessor(ProcessedMessageRepository processedMessageRepository,
                          SagaEventHandler sagaEventHandler,
                          PlatformTransactionManager transactionManager,
                          OrderProperties properties,
                          Clock clock) {
        this.processedMessageRepository = processedMessageRepository;
        this.sagaEventHandler = sagaEventHandler;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.consumerName = properties.messaging().consumerName();
        this.clock = clock;
    }

    public void process(InboundEvent event) {
        transactionTemplate.executeWithoutResult(status -> {
            int inserted = processedMessageRepository.insertIfAbsent(
                    consumerName, event.eventId(), Instants.millis(clock.instant()));
            if (inserted == 0) {
                log.info("Skipping duplicate event orderId={} eventId={} eventType={}",
                        event.orderId(), event.eventId(), event.eventType());
                return;
            }
            sagaEventHandler.handle(event);
        });
    }
}
