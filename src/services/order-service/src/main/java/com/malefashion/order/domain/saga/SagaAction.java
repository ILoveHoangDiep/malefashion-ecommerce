package com.malefashion.order.domain.saga;

/**
 * Hành động state machine quyết định nhưng chưa thực hiện. Tầng application ở giai đoạn 2 sẽ dịch
 * từng action thành ghi outbox hoặc log.
 */
public sealed interface SagaAction {

    record SendProcessPayment() implements SagaAction {
    }

    record SendCommitStock() implements SagaAction {
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
