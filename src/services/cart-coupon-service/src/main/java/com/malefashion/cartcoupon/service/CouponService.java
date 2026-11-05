package com.malefashion.cartcoupon.service;

import com.malefashion.cartcoupon.api.error.CouponInvalidException;
import com.malefashion.cartcoupon.domain.entity.Coupon;
import com.malefashion.cartcoupon.dto.CouponDto;
import com.malefashion.cartcoupon.repository.CouponRepository;
import com.malefashion.cartcoupon.repository.CouponUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;

    public BigDecimal validateAndCalculateDiscount(String code, BigDecimal subtotal, UUID userId) {
        if (code == null || code.isBlank()) {
            throw new CouponInvalidException("Mã giảm giá không hợp lệ", code);
        }

        Coupon coupon = couponRepository.findByCode(code.trim().toUpperCase())
                .orElseThrow(() -> new CouponInvalidException(
                        "Mã giảm giá không tồn tại hoặc đã bị vô hiệu hóa: " + code, code));

        if (!Boolean.TRUE.equals(coupon.getActive())) {
            throw new CouponInvalidException("Mã giảm giá không tồn tại hoặc đã bị vô hiệu hóa: " + code, code);
        }

        Instant now = Instant.now();
        if (coupon.getStartsAt() != null && now.isBefore(coupon.getStartsAt())) {
            throw new CouponInvalidException("Mã giảm giá đã hết hạn hoặc chưa đến ngày áp dụng: " + code, code);
        }
        if (coupon.getExpiresAt() != null && now.isAfter(coupon.getExpiresAt())) {
            throw new CouponInvalidException("Mã giảm giá đã hết hạn hoặc chưa đến ngày áp dụng: " + code, code);
        }

        BigDecimal minOrder = coupon.getMinOrderAmount() != null ? coupon.getMinOrderAmount() : BigDecimal.ZERO;
        if (subtotal != null && subtotal.compareTo(minOrder) < 0) {
            throw new CouponInvalidException("Đơn hàng chưa đạt giá trị tối thiểu " + minOrder + " VND", code);
        }

        if (coupon.getUsageLimit() != null && coupon.getUsedCount() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new CouponInvalidException("Mã giảm giá đã hết lượt sử dụng", code);
        }

        if (userId != null && couponUsageRepository.existsByCouponIdAndUserId(coupon.getId(), userId)) {
            throw new CouponInvalidException("Bạn đã sử dụng mã giảm giá này rồi", code);
        }

        BigDecimal discount = BigDecimal.ZERO;
        String type = coupon.getDiscountType();
        if ("FIXED_AMOUNT".equalsIgnoreCase(type)) {
            discount = coupon.getDiscountValue().min(subtotal != null ? subtotal : BigDecimal.ZERO);
        } else if ("PERCENTAGE".equalsIgnoreCase(type)) {
            if (subtotal != null) {
                discount = subtotal.multiply(coupon.getDiscountValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                if (coupon.getMaxDiscountAmount() != null) {
                    discount = discount.min(coupon.getMaxDiscountAmount());
                }
            }
        }

        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    public List<CouponDto> getAvailableCoupons() {
        return couponRepository.findByActiveTrue().stream()
                .map(c -> CouponDto.builder()
                        .id(c.getId())
                        .code(c.getCode())
                        .description(c.getDescription())
                        .discountType(c.getDiscountType())
                        .discountValue(c.getDiscountValue())
                        .minOrderAmount(c.getMinOrderAmount())
                        .maxDiscountAmount(c.getMaxDiscountAmount())
                        .startsAt(c.getStartsAt())
                        .expiresAt(c.getExpiresAt())
                        .build())
                .toList();
    }
}
