package com.malefashion.order.infrastructure.messaging;

import com.malefashion.order.domain.support.MoneyAmounts;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Tiền trong contract: lượng là string decimal 2 chữ số, không phải JSON number.
 */
public record Money(String amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money vnd(BigDecimal amount) {
        return new Money(MoneyAmounts.format(amount), MoneyAmounts.CURRENCY);
    }

    public BigDecimal value() {
        return new BigDecimal(amount);
    }
}
