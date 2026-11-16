package com.malefashion.inventory.domain.model;

import com.malefashion.inventory.domain.exception.InsufficientReservedQuantityException;
import com.malefashion.inventory.domain.exception.InsufficientStockException;
import com.malefashion.inventory.domain.exception.InvalidQuantityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockItemTest {

    private static final UUID VARIANT_ID = UUID.fromString("52e1d8ae-7c8f-5ec8-9ce7-a4c81caed6f5");
    private static final Instant NOW = Instant.parse("2026-10-22T03:15:30.123Z");

    @Test
    void createsItemWithAllStockAvailableAndNoneReserved() {
        StockItem item = StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 10, NOW);

        assertThat(item.getVariantId()).isEqualTo(VARIANT_ID);
        assertThat(item.getSku()).isEqualTo("MF-TEE-BLACK-M");
        assertThat(item.getAvailableQuantity()).isEqualTo(10);
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(item.getCreatedAt()).isEqualTo(NOW);
        assertThat(item.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void reserveMovesQuantityFromAvailableToReserved() {
        StockItem item = StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 10, NOW);

        item.reserve(4, NOW.plusSeconds(1));

        assertThat(item.getAvailableQuantity()).isEqualTo(6);
        assertThat(item.getReservedQuantity()).isEqualTo(4);
        assertThat(item.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void reserveFailsWhenNotEnoughStockAndLeavesItemUnchanged() {
        StockItem item = StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 3, NOW);

        assertThatThrownBy(() -> item.reserve(4, NOW.plusSeconds(1)))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(exception -> {
                    InsufficientStockException stockException = (InsufficientStockException) exception;
                    assertThat(stockException.getVariantId()).isEqualTo(VARIANT_ID);
                    assertThat(stockException.getRequestedQuantity()).isEqualTo(4);
                    assertThat(stockException.getAvailableQuantity()).isEqualTo(3);
                });

        assertThat(item.getAvailableQuantity()).isEqualTo(3);
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(item.getUpdatedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void reserveRejectsNonPositiveQuantity(int quantity) {
        StockItem item = StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 10, NOW);

        assertThatThrownBy(() -> item.reserve(quantity, NOW))
                .isInstanceOf(InvalidQuantityException.class);
    }

    @Test
    void commitRemovesReservedUnitsAndLeavesAvailableUntouched() {
        StockItem item = StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 10, NOW);
        item.reserve(4, NOW.plusSeconds(1));

        item.commit(4, NOW.plusSeconds(2));

        assertThat(item.getAvailableQuantity()).isEqualTo(6);
        assertThat(item.getReservedQuantity()).isZero();
    }

    @Test
    void commitFailsWhenReservedQuantityIsTooLow() {
        StockItem item = StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 10, NOW);
        item.reserve(2, NOW.plusSeconds(1));

        assertThatThrownBy(() -> item.commit(3, NOW.plusSeconds(2)))
                .isInstanceOf(InsufficientReservedQuantityException.class);

        assertThat(item.getAvailableQuantity()).isEqualTo(8);
        assertThat(item.getReservedQuantity()).isEqualTo(2);
    }

    @Test
    void releaseReturnsReservedUnitsToAvailable() {
        StockItem item = StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 10, NOW);
        item.reserve(4, NOW.plusSeconds(1));

        item.release(4, NOW.plusSeconds(2));

        assertThat(item.getAvailableQuantity()).isEqualTo(10);
        assertThat(item.getReservedQuantity()).isZero();
    }

    @Test
    void releaseFailsWhenReservedQuantityIsTooLow() {
        StockItem item = StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 10, NOW);
        item.reserve(1, NOW.plusSeconds(1));

        assertThatThrownBy(() -> item.release(2, NOW.plusSeconds(2)))
                .isInstanceOf(InsufficientReservedQuantityException.class);

        assertThat(item.getAvailableQuantity()).isEqualTo(9);
        assertThat(item.getReservedQuantity()).isEqualTo(1);
    }

    @Test
    void totalQuantityIsConservedAcrossReserveCommitAndRelease() {
        StockItem item = StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", 10, NOW);
        int total = item.getAvailableQuantity() + item.getReservedQuantity();

        item.reserve(3, NOW.plusSeconds(1));
        assertThat(item.getAvailableQuantity() + item.getReservedQuantity()).isEqualTo(total);

        item.commit(1, NOW.plusSeconds(2));
        assertThat(item.getAvailableQuantity() + item.getReservedQuantity()).isEqualTo(total - 1);

        item.release(2, NOW.plusSeconds(3));
        assertThat(item.getAvailableQuantity() + item.getReservedQuantity()).isEqualTo(total - 1);
        assertThat(item.getAvailableQuantity()).isEqualTo(9);
        assertThat(item.getReservedQuantity()).isZero();
    }

    @Test
    void createRejectsNegativeInitialQuantity() {
        assertThatThrownBy(() -> StockItem.create(VARIANT_ID, "MF-TEE-BLACK-M", -1, NOW))
                .isInstanceOf(InvalidQuantityException.class);
    }
}
