package com.malefashion.payment.infrastructure.messaging.event;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tiền trong payload: {@code amount} là string đúng 2 chữ số thập phân, {@code currency} luôn VND.
 */
public record Money(
        String amount,
        String currency
) {

    public static final String CURRENCY_VND = "VND";

    /**
     * Chuẩn hóa số tiền về dạng contract. Không làm tròn ngầm: giá trị có quá 2 chữ số thập phân
     * sẽ ném {@link ArithmeticException} để caller coi là command không hợp lệ.
     */
    public static Money vnd(BigDecimal value) {
        BigDecimal scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        return new Money(scaled.toPlainString(), CURRENCY_VND);
    }
}
