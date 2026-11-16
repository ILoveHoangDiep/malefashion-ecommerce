package com.malefashion.catalog.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Getter
@ToString
@EqualsAndHashCode
public class MoneyDto {

    private final String amount;
    private final String currency;

    @JsonCreator
    public MoneyDto(
            @JsonProperty("amount") String amount,
            @JsonProperty("currency") String currency) {
        this.amount = amount;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "VND";
    }

    public static MoneyDto of(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        String formatted = String.format(Locale.US, "%.2f", amount.setScale(2, RoundingMode.HALF_UP));
        return new MoneyDto(formatted, "VND");
    }

    public BigDecimal toBigDecimal() {
        return amount != null ? new BigDecimal(amount) : BigDecimal.ZERO;
    }
}
