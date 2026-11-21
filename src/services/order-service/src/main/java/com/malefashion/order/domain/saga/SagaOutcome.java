package com.malefashion.order.domain.saga;

/**
 * Kết quả xử lý một event/command đối với aggregate.
 */
public enum SagaOutcome {
    /** Event đã làm thay đổi trạng thái (hoặc được ghi nhận hợp lệ). */
    APPLIED,
    /** Bản lặp của event đã xử lý trước đó. */
    IGNORED_DUPLICATE,
    /** Event không áp dụng cho bước hiện tại; bỏ qua, không ném lỗi. */
    IGNORED_NOT_APPLICABLE
}
