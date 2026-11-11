package com.malefashion.cartcoupon.service;

import com.malefashion.cartcoupon.api.error.CouponInvalidException;
import com.malefashion.cartcoupon.domain.entity.Coupon;
import com.malefashion.cartcoupon.dto.CouponDto;
import com.malefashion.cartcoupon.repository.CouponRepository;
import com.malefashion.cartcoupon.repository.CouponUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private CouponUsageRepository couponUsageRepository;

    @InjectMocks
    private CouponService couponService;

    private UUID userId;
    private Coupon fixedCoupon;
    private Coupon percentCoupon;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        fixedCoupon = Coupon.builder()
                .id(UUID.randomUUID())
                .code("FREESHIP")
                .discountType("FIXED_AMOUNT")
                .discountValue(new BigDecimal("30000.00"))
                .minOrderAmount(new BigDecimal("150000.00"))
                .active(true)
                .startsAt(Instant.now().minus(5, ChronoUnit.DAYS))
                .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .usageLimit(100)
                .usedCount(10)
                .build();

        percentCoupon = Coupon.builder()
                .id(UUID.randomUUID())
                .code("SUMMER10")
                .discountType("PERCENTAGE")
                .discountValue(new BigDecimal("10.00"))
                .minOrderAmount(new BigDecimal("200000.00"))
                .maxDiscountAmount(new BigDecimal("50000.00"))
                .active(true)
                .startsAt(Instant.now().minus(5, ChronoUnit.DAYS))
                .expiresAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .usageLimit(500)
                .usedCount(5)
                .build();
    }

    @Test
    @DisplayName("Fixed amount coupon calculates discount correctly when eligible")
    void testValidateAndCalculate_FixedAmount_Success() {
        when(couponRepository.findByCode("FREESHIP")).thenReturn(Optional.of(fixedCoupon));
        when(couponUsageRepository.existsByCouponIdAndUserId(fixedCoupon.getId(), userId)).thenReturn(false);

        BigDecimal discount = couponService.validateAndCalculateDiscount("FREESHIP", new BigDecimal("250000.00"), userId);

        assertThat(discount).isEqualByComparingTo("30000.00");
    }

    @Test
    @DisplayName("Percentage coupon calculates discount and caps at maxDiscountAmount")
    void testValidateAndCalculate_PercentageWithCap() {
        when(couponRepository.findByCode("SUMMER10")).thenReturn(Optional.of(percentCoupon));
        when(couponUsageRepository.existsByCouponIdAndUserId(percentCoupon.getId(), userId)).thenReturn(false);

        // 10% of 800,000 is 80,000, capped at 50,000
        BigDecimal discount = couponService.validateAndCalculateDiscount("SUMMER10", new BigDecimal("800000.00"), userId);

        assertThat(discount).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("Throws CouponInvalidException when coupon is expired")
    void testValidateAndCalculate_Expired() {
        fixedCoupon.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(couponRepository.findByCode("FREESHIP")).thenReturn(Optional.of(fixedCoupon));

        assertThatThrownBy(() -> couponService.validateAndCalculateDiscount("FREESHIP", new BigDecimal("300000.00"), userId))
                .isInstanceOf(CouponInvalidException.class)
                .hasMessageContaining("hết hạn");
    }

    @Test
    @DisplayName("Throws CouponInvalidException when order subtotal is below minOrderAmount")
    void testValidateAndCalculate_MinOrderNotMet() {
        when(couponRepository.findByCode("FREESHIP")).thenReturn(Optional.of(fixedCoupon));

        assertThatThrownBy(() -> couponService.validateAndCalculateDiscount("FREESHIP", new BigDecimal("100000.00"), userId))
                .isInstanceOf(CouponInvalidException.class)
                .hasMessageContaining("chưa đạt giá trị tối thiểu");
    }

    @Test
    @DisplayName("Throws CouponInvalidException when usage limit is reached")
    void testValidateAndCalculate_UsageLimitReached() {
        fixedCoupon.setUsedCount(100);
        when(couponRepository.findByCode("FREESHIP")).thenReturn(Optional.of(fixedCoupon));

        assertThatThrownBy(() -> couponService.validateAndCalculateDiscount("FREESHIP", new BigDecimal("300000.00"), userId))
                .isInstanceOf(CouponInvalidException.class)
                .hasMessageContaining("hết lượt sử dụng");
    }

    @Test
    @DisplayName("Throws CouponInvalidException when user has already used this coupon")
    void testValidateAndCalculate_AlreadyUsedByUser() {
        when(couponRepository.findByCode("FREESHIP")).thenReturn(Optional.of(fixedCoupon));
        when(couponUsageRepository.existsByCouponIdAndUserId(fixedCoupon.getId(), userId)).thenReturn(true);

        assertThatThrownBy(() -> couponService.validateAndCalculateDiscount("FREESHIP", new BigDecimal("300000.00"), userId))
                .isInstanceOf(CouponInvalidException.class)
                .hasMessageContaining("đã sử dụng mã giảm giá này");
    }

    @Test
    @DisplayName("getAvailableCoupons returns active coupons list")
    void testGetAvailableCoupons() {
        when(couponRepository.findByActiveTrue()).thenReturn(List.of(fixedCoupon, percentCoupon));

        List<CouponDto> coupons = couponService.getAvailableCoupons();

        assertThat(coupons).hasSize(2);
        assertThat(coupons).extracting(CouponDto::getCode).containsExactly("FREESHIP", "SUMMER10");
    }
}
