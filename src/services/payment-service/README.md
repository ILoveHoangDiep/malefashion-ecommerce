# payment-service

Service thanh toán **mock** của hệ thống malefashion. Nhận command `payment.process-payment` và
`payment.refund-payment` từ `order-service`, phát `payment.succeeded`, `payment.failed`,
`payment.refunded`. Luồng nghiệp vụ và RabbitMQ sẽ được bổ sung ở các prompt sau; hiện tại service chỉ có
khung dự án, cấu hình và database.

- Java 21, Spring Boot 3.5.16, PostgreSQL 16, Spring AMQP.
- Cổng mặc định: **8086**. Múi giờ: **UTC**. Database: **payment_db**.
- Chỉ mở `GET /actuator/health`; chưa có REST nghiệp vụ.
- Service **không** tự khai báo exchange/queue/binding. Topology do
  `deploy/rabbitmq/definitions.json` quản lý (queue `payment-service.commands`, DLQ
  `payment-service.commands.dlq`).

## 1. Database

`scripts/init-multiple-dbs.sh` ở gốc repo được compose gọi với
`POSTGRES_MULTIPLE_DATABASES=identity_db,catalog_db,cart_db,order_db,inventory_db,payment_db,notification_db`,
nên PostgreSQL local đã tạo sẵn `payment_db`. Nếu container cũ được tạo trước khi `payment_db` có trong
danh sách, hoặc script init không chạy, tạo thủ công:

```powershell
docker start malefashion-postgres
docker exec malefashion-postgres psql -U postgres -c "CREATE DATABASE payment_db;"
```

Flyway chạy `src/main/resources/db/migration/V1__init.sql` khi khởi động. Các bảng:

| Bảng | Mục đích |
|---|---|
| `payments` | Một payment cho mỗi `order_id`; lưu amount, currency, status, scenario mock, mã giao dịch provider, lỗi, mốc thời gian |
| `refunds` | Một refund cho mỗi payment/order; khóa ngoại tới `payments` |
| `outbox_events` | Transactional outbox, giống inventory-service |
| `processed_messages` | Inbox idempotent theo `(consumer, message_id)`, giống inventory-service |

Sequence `payment_provider_transaction_id_seq` cấp số cho `providerTransactionId` (định dạng dự kiến
`MOCK-TXN-...` sẽ chốt ở prompt nghiệp vụ).

Ràng buộc chính: `amount > 0`, `currency = 'VND'`, `status` thuộc
`SUCCEEDED | FAILED | REFUNDED`, `payments.order_id` unique, `refunds.payment_id` và `refunds.order_id`
unique.

## 2. Chạy local bằng PowerShell

```powershell
cd D:\malefashion-ecommerce
docker compose -f deploy\docker-compose\docker-compose.infra.yml up -d

cd src\services\payment-service
$env:SPRING_PROFILES_ACTIVE = "local"    # bật mock scenario + log DEBUG
.\mvnw.cmd spring-boot:run
```

Kiểm tra <http://localhost:8086/actuator/health>.

| Biến | Default | Ý nghĩa |
|---|---|---|
| `PAYMENT_SERVICE_PORT` | `8086` | Cổng HTTP |
| `POSTGRES_HOST` / `POSTGRES_PORT` | `localhost` / `5432` | PostgreSQL |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `postgres` / `postgrespassword` | Tài khoản DB local |
| `PAYMENT_DB_NAME` | `payment_db` | Tên database |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | RabbitMQ AMQP |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Tài khoản RabbitMQ local |
| `RABBITMQ_VHOST` | `/` | Virtual host |
| `PAYMENT_MESSAGE_MAX_ATTEMPTS` | `3` | Số lần xử lý message trước khi vào DLQ |
| `PAYMENT_OUTBOX_PUBLISH_INTERVAL_MS` | `500` | Chu kỳ outbox publisher |
| `PAYMENT_MOCK_HONOR_SCENARIO` | `false` (`true` ở profile `local`/`demo`) | Cho phép command chỉ định `mockScenario` |
| `PAYMENT_MOCK_SILENT_ON_TIMEOUT` | `false` | Chế độ im lặng khi mock timeout |

Không commit mật khẩu thật; dùng biến môi trường hoặc secret manager khi chạy thật.

## 3. Test

Yêu cầu Docker Desktop đang chạy. Test tích hợp dùng Testcontainers thật (`postgres:16-alpine`), không dùng
H2 hay mock database.

```powershell
cd D:\malefashion-ecommerce\src\services\payment-service
.\mvnw.cmd -B clean verify
```

- `PaymentServiceContextIT`: context load, Flyway chạy trên PostgreSQL 16 thật, entity round-trip và
  OutboxService ghi đúng envelope.
- `PaymentSchemaConstraintIT`: amount <= 0, trùng `order_id`, trùng `payment_id` trong refunds, currency
  khác VND và trùng `message_id` trong `processed_messages` đều bị PostgreSQL chặn.

Các lớp `*IT` chạy ở phase `integration-test` của Maven Failsafe, nên `verify` là lệnh đầy đủ.

## 4. Build Docker (tuỳ chọn)

```powershell
docker build -t malefashion/payment-service:dev src/services/payment-service
```

Container chạy bằng user `app` (uid 100), expose 8086. Test không chạy trong image; chạy
`.\mvnw.cmd clean verify` ở máy trước khi build.
