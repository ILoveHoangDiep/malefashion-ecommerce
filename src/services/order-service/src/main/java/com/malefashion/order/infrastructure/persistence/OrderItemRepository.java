package com.malefashion.order.infrastructure.persistence;

import com.malefashion.order.domain.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
}
