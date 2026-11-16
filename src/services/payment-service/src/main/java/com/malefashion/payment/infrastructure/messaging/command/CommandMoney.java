package com.malefashion.payment.infrastructure.messaging.command;

import java.math.BigDecimal;

/**
 * Tiền trong payload command: {@code {"amount":"578000.00","currency":"VND"}}.
 */
public record CommandMoney(
        String amount,
        String currency
) {

    public BigDecimal toAmount() {
        return amount == null ? null : new BigDecimal(amount);
    }
}
