package com.malefashion.payment.application;

/**
 * Kết quả của một lệnh payment.refund-payment.
 */
public enum RefundOutcome {

    /** Không tìm thấy payment, paymentId lệch hoặc payment đã FAILED: không phát event. */
    NO_EVENT,

    /** Đã tạo refund mới và phát payment.refunded. */
    REFUNDED,

    /** Payment đã REFUNDED từ trước: phát lại payment.refunded với refundId cũ. */
    EVENT_REPLAYED
}
