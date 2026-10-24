package com.malefashion.inventory.domain.model;

import com.malefashion.inventory.domain.exception.InvalidQuantityException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "stock_reservation_items")
public class ReservationItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected ReservationItem() {
        // for JPA
    }

    private ReservationItem(UUID variantId, String sku, int quantity) {
        this.id = UUID.randomUUID();
        this.variantId = variantId;
        this.sku = sku;
        this.quantity = quantity;
    }

    public static ReservationItem create(UUID variantId, String sku, int quantity) {
        Objects.requireNonNull(variantId, "variantId must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new InvalidQuantityException(quantity);
        }
        return new ReservationItem(variantId, sku, quantity);
    }

    void attachTo(Reservation reservation) {
        this.reservation = reservation;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }
}
