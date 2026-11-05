package com.malefashion.payment.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderTransactionIdGeneratorTest {

    @Test
    void formatsDateAndSequenceIntoContractShape() {
        assertThat(ProviderTransactionIdGenerator.format(LocalDate.of(2026, 10, 22), 1))
                .isEqualTo("MOCK-TXN-20261022-000001");
        assertThat(ProviderTransactionIdGenerator.format(LocalDate.of(2026, 10, 22), 42))
                .isEqualTo("MOCK-TXN-20261022-000042");
        assertThat(ProviderTransactionIdGenerator.format(LocalDate.of(2026, 10, 22), 999999))
                .isEqualTo("MOCK-TXN-20261022-999999");
    }

    @Test
    void padsSequenceToAtLeastSixDigits() {
        assertThat(ProviderTransactionIdGenerator.format(LocalDate.of(2026, 1, 5), 7))
                .matches("^MOCK-TXN-\\d{8}-\\d{6,}$")
                .isEqualTo("MOCK-TXN-20260105-000007");
    }

    @Test
    void keepsGrowingBeyondSixDigitsWithoutTruncation() {
        assertThat(ProviderTransactionIdGenerator.format(LocalDate.of(2026, 10, 22), 1_234_567))
                .isEqualTo("MOCK-TXN-20261022-1234567");
    }
}
