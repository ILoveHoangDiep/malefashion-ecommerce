package com.malefashion.cartcoupon.repository;

import com.malefashion.cartcoupon.domain.entity.CouponUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CouponUsageRepository extends JpaRepository<CouponUsage, UUID> {

    boolean existsByCouponIdAndUserId(UUID couponId, UUID userId);
}
