# Cart & Coupon Service (MaleFashion)

Quản lý trạng thái giỏ hàng thời gian thực của người dùng trên Redis và động cơ kiểm tra / tính toán mã giảm giá (Voucher Engine) trên cơ sở dữ liệu PostgreSQL.

## 1. Công nghệ sử dụng
- **Java 17 / Spring Boot 3.3.5**
- **Spring Data Redis** (Lưu trữ giỏ hàng POJO JSON `cart:{userId}`, TTL 30 ngày)
- **Spring Data JPA + PostgreSQL** (database `cart_db` lưu `coupons` và `coupon_usages`)
- **Flyway Migration** (`V1__init_coupon_schema.sql`, `V2__seed_coupons.sql`)
- **ProblemDetail RFC 9457** (`application/problem+json`)

## 2. API Endpoints
### Nội bộ (Internal - gọi giữa các microservices):
- `GET /internal/v1/carts/{userId}`: Lấy snapshot giỏ hàng kèm coupon đang áp dụng cho order-service khi checkout. Trả về `404` nếu chưa có giỏ, `409` nếu giỏ rỗng, `422` nếu mã coupon không hợp lệ/hết hạn.

### Storefront (Public qua Gateway):
- `GET /api/v1/cart`: Lấy thông tin giỏ hàng hiện tại (header `X-User-Id`).
- `POST /api/v1/cart/items`: Thêm sản phẩm vào giỏ hàng.
- `PUT /api/v1/cart/items/{variantId}`: Cập nhật số lượng item (quantity = 0 sẽ tự xóa).
- `DELETE /api/v1/cart/items/{variantId}`: Xóa item khỏi giỏ hàng.
- `DELETE /api/v1/cart`: Xóa sạch giỏ hàng.
- `POST /api/v1/cart/coupons`: Áp dụng mã coupon (`SUMMER10`, `WELCOME20`, `FREESHIP`).
- `DELETE /api/v1/cart/coupons`: Hủy mã coupon khỏi giỏ hàng.
- `GET /api/v1/cart/coupons`: Danh sách coupon đang kích hoạt.

## 3. Khởi chạy cục bộ
```bash
# Đảm bảo hạ tầng Docker đã chạy:
docker compose -f deploy/docker-compose/docker-compose.infra.yml up -d

# Chạy service:
mvn spring-boot:run
```
Service sẽ lắng nghe tại cổng `8083`.
Actuator Health: `http://localhost:8083/actuator/health`

## 4. Chạy kiểm thử
```bash
mvn test
```
