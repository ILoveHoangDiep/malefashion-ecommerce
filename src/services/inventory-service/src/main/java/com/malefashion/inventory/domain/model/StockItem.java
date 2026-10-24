package com.malefashion.inventory.domain.model;

import com.malefashion.inventory.domain.exception.InsufficientReservedQuantityException;
import com.malefashion.inventory.domain.exception.InsufficientStockException;
import com.malefashion.inventory.domain.exception.InvalidQuantityException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stock level of one variant.
 *
 * <p>Invariant: {@code availableQuantity >= 0} and {@code reservedQuantity >= 0}.
 * Stock is moved between the two buckets, never created or destroyed by reserve/commit/release.</p>
 */
@Entity
@Table(name = "stock_items")
public class StockItem {

    @Id
    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "sku", nullable = false, unique = true, length = 64)
    private String sku;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockItem() {
        // for JPA
    }

    private StockItem(UUID variantId, String sku, int availableQuantity, Instant now) {
        this.variantId = variantId;
        this.sku = sku;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static StockItem create(UUID variantId, String sku, int initialQuantity, Instant now) {
        Objects.requireNonNull(variantId, "variantId must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (initialQuantity < 0) {
            throw new InvalidQuantityException(initialQuantity);
        }
        return new StockItem(variantId, sku, initialQuantity, now);
    }

    /**
     * Moves {@code quantity} units from available to reserved.
     */
    public void reserve(int quantity, Instant now) {
        requirePositive(quantity);
        Objects.requireNonNull(now, "now must not be null");
        if (availableQuantity < quantity) {
            throw new InsufficientStockException(variantId, quantity, availableQuantity);
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
        updatedAt = now;
    }

    /**
     * Permanently removes {@code quantity} reserved units from stock.
     */
    public void commit(int quantity, Instant now) {
        requirePositive(quantity);
        Objects.requireNonNull(now, "now must not be null");
        if (reservedQuantity < quantity) {
            throw new InsufficientReservedQuantityException(variantId, quantity, reservedQuantity);
        }
        reservedQuantity -= quantity;
        updatedAt = now;
    }

    /**
     * Moves {@code quantity} reserved units back to available.
     */
    public void release(int quantity, Instant now) {
        requirePositive(quantity);
        Objects.requireNonNull(now, "now must not be null");
        if (reservedQuantity < quantity) {
            throw new InsufficientReservedQuantityException(variantId, quantity, reservedQuantity);
        }
        reservedQuantity -= quantity;
        availableQuantity += quantity;
        updatedAt = now;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new InvalidQuantityException(quantity);
        }
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
