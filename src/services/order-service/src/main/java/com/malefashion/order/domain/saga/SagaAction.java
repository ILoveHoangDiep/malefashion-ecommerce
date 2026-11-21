package com.malefashion.order.domain.saga;

/**
 * Hành động state machine quyết định nhưng chưa thực hiện. Tầng application ở giai đoạn 2 sẽ dịch
 * từng action thành ghi outbox hoặc log.
 */
public sealed interface SagaAction {

    record SendProcessPayment() implements SagaAction {
    }

    /**
     * @param retry false cho lần gửi đầu, true cho các lần gửi lại sau commit deadline. Cả hai đều
     *              dùng đúng {@code commitCommandEventId} đã lưu trên order.
     */
    record SendCommitStock(boolean retry) implements SagaAction {
    }

    record SendReleaseStock(StockReleaseReason reason) implements SagaAction {
    }

    record SendRefund(boolean retry) implements SagaAction {
    }

    record EmitOrderConfirmed() implements SagaAction {
    }

    record EmitOrderCancelled() implements SagaAction {
    }

    record LogWarn(String code) implements SagaAction {
    }

    record LogError(String code) implements SagaAction {
    }
}
