package com.malefashion.order.infrastructure.persistence;

import com.malefashion.order.domain.model.Order;
import com.malefashion.order.domain.model.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    /**
     * Trang chỉ gồm root order (không fetch collection cùng pagination để tránh phân trang trong
     * bộ nhớ); items được nạp một lần bằng {@link #findAllWithItemsByIdIn(Collection)}.
     */
    Page<Order> findByUserId(UUID userId, Pageable pageable);

    Page<Order> findByUserIdAndStatus(UUID userId, OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    List<Order> findAllWithItemsByIdIn(Collection<UUID> ids);

    /**
     * Khóa ghi trên dòng order để tuần tự hóa mọi đường ghi (event saga và scheduler hạn chờ).
     * Không dùng entity graph cùng khóa vì Postgres không cho FOR UPDATE trên phía nullable của
     * outer join; items được nạp lazy trong cùng transaction khi cần.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);
}
