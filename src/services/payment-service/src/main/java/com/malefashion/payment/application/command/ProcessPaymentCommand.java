package com.malefashion.payment.application.command;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lệnh payment.process-payment đã được parse từ envelope.
 *
 * <p>{@code mockScenario} là chuỗi thô trong command; việc quyết định có dùng hay không thuộc về
 * {@code MockScenarioResolver} để xử lý được cả trường hợp thiếu field hoặc giá trị lạ.</p>
 */
public record ProcessPaymentCommand(
        UUID orderId,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String mockScenario
) {
}
