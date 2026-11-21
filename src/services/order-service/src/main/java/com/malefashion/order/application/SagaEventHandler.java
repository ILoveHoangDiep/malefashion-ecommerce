package com.malefashion.order.application;

import com.malefashion.order.infrastructure.messaging.InboundEvent;

/**
 * Xử lý nghiệp vụ saga cho một event đã dedupe. Chạy trong cùng transaction với marker inbox nên
 * exception làm rollback cả hai.
 */
public interface SagaEventHandler {

    void handle(InboundEvent event);
}
