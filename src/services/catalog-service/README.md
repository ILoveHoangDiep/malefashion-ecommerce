# Catalog Service (MaleFashion)

Quản lý danh mục, sản phẩm, các biến thể thời trang (Size, Color, SKU), lưu trữ và phân phối hình ảnh sản phẩm qua MinIO S3 SDK, tích hợp Redis Cache-aside và nạp dữ liệu mẫu ban đầu (Seed Loader).

## 1. Công nghệ sử dụng
- **Java 17 / Spring Boot 3.3.5**
- **Spring Data JPA + PostgreSQL** (database `catalog_db`)
- **Flyway Migration** (`V1__init_catalog_schema.sql`)
- **Redis Cache-aside** (`@Cacheable` với 10 phút TTL)
- **MinIO S3 Client** (`catalog-images` bucket)
- **ProblemDetail RFC 9457** (`application/problem+json`)

## 2. API Endpoints
### Nội bộ (Internal - gọi giữa các microservices):
- `POST /internal/v1/variants/batch`: Lấy danh sách thông tin chi tiết nhiều variant cùng lúc (dùng khi re-validate đơn hàng lúc checkout). Trả về danh sách variant tìm thấy và mảng `missingIds`.

### Storefront (Public qua Gateway):
- `GET /api/v1/products`: Danh sách sản phẩm (có phân trang, filter theo categoryId, cache Redis).
- `GET /api/v1/products/{id}`: Chi tiết sản phẩm kèm danh sách biến thể (cache Redis).
- `GET /api/v1/categories`: Danh mục sản phẩm (cache Redis).

## 3. Khởi chạy cục bộ
```bash
# Đảm bảo hạ tầng Docker đã chạy:
docker compose -f deploy/docker-compose/docker-compose.infra.yml up -d

# Chạy service:
mvn spring-boot:run
```
Service sẽ lắng nghe tại cổng `8082`.
Actuator Health: `http://localhost:8082/actuator/health`

## 4. Chạy kiểm thử
```bash
mvn test
```
