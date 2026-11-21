package com.malefashion.order.application;

import com.malefashion.order.infrastructure.messaging.InboundEvent;

/**
 * Tạm thời cho tới giai đoạn 2b. IllegalStateException là lỗi chưa phân loại nên listener retry đủ
 * số lần rồi DLQ; chưa có test nào chạy listener trước 2b.
 */
public class PendingSagaEventHandler implements SagaEventHandler {

    @Override
    public void handle(InboundEvent event) {
        throw new IllegalStateException("saga handler not implemented until stage 2b");
    }
}
