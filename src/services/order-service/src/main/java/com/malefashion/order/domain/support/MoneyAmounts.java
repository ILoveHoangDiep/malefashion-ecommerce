package com.malefashion.order.domain.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * Tiền luôn là BigDecimal scale 2 khi lưu và chuỗi decimal đúng contract khi phát JSON.
 */
public final class MoneyAmounts {

    public static final String CURRENCY = "VND";

    public static final Pattern AMOUNT_PATTERN = Pattern.compile("^(0|[1-9][0-9]*)\\.[0-9]{2}$");

    private MoneyAmounts() {
    }

    public static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        return scale(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    public static BigDecimal total(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal shippingFee) {
        return scale(subtotal.subtract(discountAmount).add(shippingFee));
    }

    public static String format(BigDecimal value) {
        return scale(value).toPlainString();
    }

    public static boolean hasContractFormat(BigDecimal value) {
        return value != null && AMOUNT_PATTERN.matcher(format(value)).matches();
    }
}
