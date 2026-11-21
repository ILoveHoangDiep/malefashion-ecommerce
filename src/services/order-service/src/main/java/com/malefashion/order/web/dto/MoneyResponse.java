package com.malefashion.order.web.dto;

import com.malefashion.order.domain.support.MoneyAmounts;

import java.math.BigDecimal;

/**
 * Tiền trong response luôn là string decimal 2 chữ số theo common.yaml, không dùng number.
 */
public record MoneyResponse(String amount, String currency) {

    public static MoneyResponse vnd(BigDecimal value) {
        return new MoneyResponse(MoneyAmounts.format(value), MoneyAmounts.CURRENCY);
    }
}
