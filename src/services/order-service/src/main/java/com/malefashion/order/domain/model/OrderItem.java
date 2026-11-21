package com.malefashion.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "size", nullable = false, length = 1)
    private String size;

    @Column(name = "color", nullable = false, length = 50)
    private String color;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal lineTotal;

    protected OrderItem() {
        // for JPA
    }

    OrderItem(UUID id,
              Order order,
              UUID variantId,
              String sku,
              String productName,
              String size,
              String color,
              int quantity,
              BigDecimal unitPrice,
              BigDecimal lineTotal) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.order = Objects.requireNonNull(order, "order must not be null");
        this.variantId = Objects.requireNonNull(variantId, "variantId must not be null");
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        this.productName = Objects.requireNonNull(productName, "productName must not be null");
        this.size = Objects.requireNonNull(size, "size must not be null");
        this.color = Objects.requireNonNull(color, "color must not be null");
        this.quantity = quantity;
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        this.lineTotal = Objects.requireNonNull(lineTotal, "lineTotal must not be null");
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

    public String getProductName() {
        return productName;
    }

    public String getSize() {
        return size;
    }

    public String getColor() {
        return color;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
