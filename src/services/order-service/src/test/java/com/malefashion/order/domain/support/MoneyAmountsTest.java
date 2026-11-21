package com.malefashion.order.domain.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyAmountsTest {

    @Test
    void computesTotalAsSubtotalMinusDiscountPlusShipping() {
        BigDecimal total = MoneyAmounts.total(
                new BigDecimal("598000.00"),
                new BigDecimal("50000.00"),
                new BigDecimal("30000.00"));

        assertThat(total).isEqualByComparingTo("578000.00");
        assertThat(total.scale()).isEqualTo(2);
    }

    @Test
    void computesLineTotalWithTwoDecimalsAndHalfUp() {
        assertThat(MoneyAmounts.lineTotal(new BigDecimal("299000.00"), 2))
                .isEqualByComparingTo("598000.00");
        assertThat(MoneyAmounts.lineTotal(new BigDecimal("100.005"), 1))
                .isEqualByComparingTo("100.01");
    }

    @Test
    void formatsAmountAsContractString() {
        assertThat(MoneyAmounts.format(new BigDecimal("299000"))).isEqualTo("299000.00");
        assertThat(MoneyAmounts.format(new BigDecimal("0"))).isEqualTo("0.00");
        assertThat(MoneyAmounts.AMOUNT_PATTERN.matcher("598000.00").matches()).isTrue();
        assertThat(MoneyAmounts.AMOUNT_PATTERN.matcher("0598000.00").matches()).isFalse();
        assertThat(MoneyAmounts.AMOUNT_PATTERN.matcher("598000").matches()).isFalse();
        assertThat(MoneyAmounts.hasContractFormat(new BigDecimal("578000.00"))).isTrue();
    }
}
