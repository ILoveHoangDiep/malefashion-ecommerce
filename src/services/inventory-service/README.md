# inventory-service

Service quản lý tồn kho của hệ thống **malefashion**. Service nhận các lệnh giữ hàng / chốt hàng / nhả hàng
từ `order-service` qua RabbitMQ, cập nhật tồn kho trong PostgreSQL, rồi phát event kết quả để saga bên
`order-service` tiếp tục. Mọi event được ghi vào **outbox** trong cùng transaction với nghiệp vụ, sau đó
mới publish lên broker; consumer xử lý idempotent theo `eventId`. Reservation có TTL 15 phút và được job
định kỳ nhả kho nếu quá hạn.

- Java 21, Spring Boot 3.5.16, PostgreSQL 16, RabbitMQ 3.
- Cổng mặc định: **8085**. Múi giờ: **UTC**.

## 1. Luồng xử lý

```text
order-service
   │  publish command (routing key = inventory.reserve-stock / commit-stock / release-stock)
   ▼
exchange malefashion.commands (topic)
   ▼
queue inventory-service.commands
   ▼
InventoryCommandListener
   ▼
MỘT transaction:
   ├─ INSERT processed_messages (consumer, message_id = eventId)  → trùng thì ack & bỏ qua
   ├─ cập nhật stock_items / stock_reservations
   └─ INSERT outbox_events (envelope JSON đầy đủ)
   ▼
OutboxPublisher (mỗi 500 ms)
   ▼  publisher confirm + mandatory/returns, chỉ SENT sau khi broker ack
exchange malefashion.events (routing key = eventType)
   ▼
queue order-service.saga.events
   ▼
order-service

Job TTL (mỗi 60 s): reservation RESERVED quá expires_at
   → nhả kho, status EXPIRED, ghi outbox stock-released (reason RESERVATION_EXPIRED)
```

## 2. Message nhận / phát và REST

### 2.1. Command service nhận

Tất cả command đi vào exchange `malefashion.commands` và queue `inventory-service.commands`.
Service **không tự khai báo** exchange/queue/binding; topology do `deploy/rabbitmq/definitions.json` quản lý.

| Command (routing key) | Queue | Khi nào order-service gửi | Kết quả |
|---|---|---|---|
| `inventory.reserve-stock` | `inventory-service.commands` | Tạo đơn, cần giữ hàng | `stock-reserved` hoặc `stock-reservation-failed` |
| `inventory.commit-stock` | `inventory-service.commands` | Thanh toán thành công, chốt hàng | `stock-committed` hoặc `stock-commit-failed` |
| `inventory.release-stock` | `inventory-service.commands` | Thanh toán thất bại / saga timeout | `stock-released` |

Command phải là envelope JSON có `eventId`, `eventType`, `eventVersion`, `occurredAt`, `correlationId`,
`payload`. Header AMQP `type` phải bằng `eventType` (nếu thiếu, service đọc `eventType` trong body).

### 2.2. Event service phát

Tất cả event đi vào exchange `malefashion.events`, routing key = `eventType`, và được bind tới queue
`order-service.saga.events` theo `definitions.json`.

| Event (routing key) | Ý nghĩa | `reason` có thể có |
|---|---|---|
| `inventory.stock-reserved` | Giữ hàng thành công (hoặc phát lại cho cùng `orderId`) | — |
| `inventory.stock-reservation-failed` | Không giữ được hàng, `failedItems` liệt kê item lỗi | `INSUFFICIENT_STOCK`, `VARIANT_NOT_FOUND`, `CONCURRENT_MODIFICATION` |
| `inventory.stock-committed` | Chốt hàng thành công (hoặc phát lại nếu đã COMMITTED) | — |
| `inventory.stock-commit-failed` | Không chốt được (reservation không tồn tại, order lệch, đã hết hạn/đã nhả) | `RESERVATION_EXPIRED` |
| `inventory.stock-released` | Đã nhả hàng về kho | `PAYMENT_FAILED`, `SAGA_TIMEOUT`, `RESERVATION_EXPIRED` |

AMQP properties của event: `messageId = eventId`, `type = eventType`, `correlationId = orderId` của event,
`contentType = application/json`, delivery mode `persistent`, header `x-event-version`.

### 2.3. REST

| Endpoint | Trạng thái | Ghi chú |
|---|---|---|
| `GET /actuator/health` | **Đã có** | Chỉ mở health (liveness/readiness), không mở endpoint khác |
| `GET /api/v1/inventory/variants/{variantId}` | **Giai đoạn 3** (chưa có) | Trả `availableQuantity`, `inStock`; 404 `VARIANT_NOT_FOUND` |
| `GET /api/v1/inventory/variants?ids=a,b` | **Giai đoạn 3** (chưa có) | Tối đa 50 id, trả `missingIds`; 400 `TOO_MANY_IDS` / `VALIDATION_ERROR` |

Lỗi REST sẽ dùng `application/problem+json` theo contract OpenAPI của nhóm.

## 3. Chạy local bằng PowerShell

### 3.1. Bật hạ tầng

Từ thư mục gốc repo:

```powershell
cd D:\malefashion-ecommerce
docker compose -f deploy\docker-compose\docker-compose.infra.yml up -d
docker compose -f deploy\docker-compose\docker-compose.infra.yml ps
```

Chờ `malefashion-postgres` và `malefashion-rabbitmq` ở trạng thái healthy/running. Script khởi tạo
PostgreSQL sẽ tạo sẵn database `inventory_db`.

### 3.2. Nạp topology RabbitMQ

Topology **không** do service tạo. Nạp `deploy/rabbitmq/definitions.json` một lần sau khi RabbitMQ chạy.

Cách 1 — Management UI: mở <http://localhost:15672>, đăng nhập bằng tài khoản dev trong
`deploy/docker-compose/docker-compose.infra.yml`, vào **Admin → Import definitions**, chọn file
`deploy\rabbitmq\definitions.json`, bấm **Import**.

Cách 2 — PowerShell:

```powershell
$repo = "D:\malefashion-ecommerce"
$user = $env:RABBITMQ_USER; if (-not $user) { $user = "guest" }        # giá trị dev mặc định
$pass = $env:RABBITMQ_PASSWORD; if (-not $pass) { $pass = "guest" }    # production phải đổi
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${user}:${pass}"))

Invoke-RestMethod `
  -Uri "http://localhost:15672/api/definitions" `
  -Method Post `
  -Headers @{ Authorization = "Basic $auth" } `
  -ContentType "application/json" `
  -Body (Get-Content -Raw "$repo\deploy\rabbitmq\definitions.json")
```

Kiểm tra nhanh trong Management UI: có queue `inventory-service.commands`, DLQ
`inventory-service.commands.dlq`, exchange `malefashion.commands`, `malefashion.events`,
`malefashion.dlx`.

### 3.3. Chạy service

```powershell
cd D:\malefashion-ecommerce\src\services\inventory-service
$env:SPRING_PROFILES_ACTIVE = "local"    # bật log DEBUG (tuỳ chọn)
.\mvnw.cmd spring-boot:run
```

Service lắng nghe ở <http://localhost:8085>; kiểm tra <http://localhost:8085/actuator/health>.

Service dùng các biến môi trường sau (đều có default cho máy local):

| Biến | Default | Ý nghĩa |
|---|---|---|
| `INVENTORY_SERVICE_PORT` | `8085` | Cổng HTTP |
| `POSTGRES_HOST` / `POSTGRES_PORT` | `localhost` / `5432` | PostgreSQL |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | theo cấu hình môi trường | Tài khoản DB (compose local đặt sẵn) |
| `INVENTORY_DB_NAME` | `inventory_db` | Tên database |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | RabbitMQ AMQP |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | theo cấu hình môi trường | Tài khoản RabbitMQ (dev mặc định của RabbitMQ) |
| `RABBITMQ_VHOST` | `/` | Virtual host |
| `INVENTORY_RESERVATION_TTL` | `PT15M` | TTL reservation |
| `INVENTORY_EXPIRY_SCAN_INTERVAL_MS` | `60000` | Chu kỳ job hết hạn |
| `INVENTORY_OUTBOX_PUBLISH_INTERVAL_MS` | `500` | Chu kỳ outbox publisher |
| `INVENTORY_MESSAGE_MAX_ATTEMPTS` | `3` | Tổng số lần xử lý message trước khi vào DLQ |

Không commit file chứa mật khẩu thật; khi chạy thật hãy đặt biến môi trường hoặc dùng secret manager.

### 3.4. Build Docker (tuỳ chọn)

```powershell
cd D:\malefashion-ecommerce\src\services\inventory-service
docker build -t malefashion/inventory-service .
```

Container chạy bằng user non-root, expose cổng 8085.

## 4. Chạy test bằng PowerShell

Yêu cầu **Docker Desktop đang chạy**: test tích hợp dùng Testcontainers thật
(`postgres:16-alpine`, `rabbitmq:3-management-alpine`), không dùng H2 hay mock DB/broker.

```powershell
cd D:\malefashion-ecommerce\src\services\inventory-service

# Toàn bộ unit + integration test
.\mvnw.cmd clean verify

# Chỉ lớp test đồng thời
.\mvnw.cmd -Dtest=ReserveStockConcurrencyTest test

# Một lớp test bất kỳ
.\mvnw.cmd -Dtest=InventoryCommandFlowIntegrationTest test
```

- Mỗi lớp test tích hợp tự dựng PostgreSQL/RabbitMQ riêng, nạp `deploy/rabbitmq/definitions.json` qua
  Management API và dọn dữ liệu trước mỗi test.
- Mọi payload trong `outbox_events` được validate bằng JSON Schema đọc trực tiếp từ `contracts/events`.
- Lần chạy đầu sẽ kéo Docker image và Maven dependency, chậm hơn các lần sau.

## 5. Thiết kế

### 5.1. Chống bán quá số lượng (oversell)

Ba lớp bảo vệ, theo thứ tự tác dụng:

1. **Khóa bi quan có thứ tự** — `StockItemLocker` đọc các dòng `stock_items` bằng
   `SELECT ... FOR UPDATE`, sắp xếp tăng dần theo `variant_id`. Khóa giữ tới khi transaction commit nên
   transaction khác phải chờ và đọc được số lượng mới nhất; thứ tự cố định tránh deadlock.
2. **Optimistic lock `@Version`** — `stock_items` và `stock_reservations` đều có cột `version`. Đây là lưới
   an toàn cho các đường ghi không đi qua locker (hoặc nếu sau này có đường ghi mới).
3. **CHECK constraint của PostgreSQL** — `available_quantity >= 0`, `reserved_quantity >= 0` là lớp cuối,
   chặn dữ liệu âm dù code có lỗi.

Nếu một lần reserve gặp xung đột khóa, service thử lại tối đa **5 lần** với backoff
`25 ms * 2^(n-1) + jitter`; mỗi lần thử mở **một transaction mới**. Hết lượt mới phát
`stock-reservation-failed` reason `CONCURRENT_MODIFICATION`. Vòng retry **không** nằm trong transaction
(transaction đã rollback-only thì không thể dùng lại).

### 5.2. Transactional outbox

- Trong cùng transaction với thay đổi tồn kho, service ghi event vào bảng `outbox_events` (payload là
  envelope JSON đầy đủ). Nếu transaction rollback, event cũng không tồn tại → không mất, không gửi sai.
- `OutboxPublisher` chạy mỗi **500 ms**, lấy tối đa **100** dòng `PENDING` bằng `FOR UPDATE SKIP LOCKED`
  (nhiều instance không publish trùng một dòng).
- Publish lên `malefashion.events` với routing key = `eventType`, bật **publisher confirms**.
- Chỉ đánh dấu `SENT` và ghi `published_at` **sau khi broker ack**.
- Bật `spring.rabbitmq.template.mandatory=true` cùng `publisher-returns=true`: nếu routing key không có
  binding, broker trả message về (`basic.return`); service coi đây là **publish thất bại**, tăng
  `attempts`, giữ `PENDING` để chu kỳ sau gửi lại.
- Kết quả là mô hình **at-least-once**: event có thể được gửi hơn một lần, nên consumer phía sau phải
  idempotent theo `eventId`.

### 5.3. Consumer idempotent

- Bảng `processed_messages(consumer, message_id)` với khóa chính `(consumer, message_id)`.
- Trong transaction xử lý command, service chạy
  `INSERT ... ON CONFLICT (consumer, message_id) DO NOTHING`. Nếu không chèn được (message đã xử lý),
  service **ack và bỏ qua**, không chạy lại nghiệp vụ.
- `message_id` lấy từ `eventId` của envelope, nên yêu cầu bắt buộc là **mỗi command có `eventId` duy nhất**;
  khi retry, order-service phải gửi lại đúng `eventId` cũ.
- Ngoài ra reserve còn idempotent theo `orderId`: nếu đã có reservation `RESERVED` cho `orderId`, service
  phát lại `stock-reserved` với `reservationId` và `expiresAt` cũ, không trừ kho thêm.

### 5.4. TTL 15 phút và job hết hạn

- Khi reserve thành công: `expires_at = now + inventory.reservation.ttl` (mặc định **15 phút**).
- Job `@Scheduled` chạy mỗi **60 giây**, tìm reservation `RESERVED` có `expires_at < now` bằng
  `FOR UPDATE SKIP LOCKED`, xử lý tối đa 100 reservation mỗi lượt. Mỗi reservation chạy trong một
  transaction riêng.
- Với mỗi reservation quá hạn: cộng lại `available_quantity`, giảm `reserved_quantity`, chuyển status
  `EXPIRED`, ghi outbox `inventory.stock-released` reason `RESERVATION_EXPIRED`.
- Commit đến sau khi reservation đã hết hạn sẽ nhận `stock-commit-failed` reason `RESERVATION_EXPIRED`
  và không thay đổi tồn kho.

### 5.5. Retry và DLQ

- Listener retry **3 lần tổng cộng** (1 lần đầu + 2 lần thử lại), backoff `500 ms × 2`, tối đa `5 s`.
- Quá 3 lần: message bị reject **không requeue** → `malefashion.dlx` → `inventory-service.commands.dlq`.
- Lỗi vĩnh viễn (JSON hỏng, thiếu field bắt buộc, `type` header lệch `eventType`, command không hỗ trợ) bị
  `AmqpRejectAndDontRequeueException` ngay, **không retry**, đi thẳng DLQ.
- Lỗi nghiệp vụ (thiếu hàng, variant không tồn tại, reservation hết hạn…) **không** retry; service phát
  event lỗi tương ứng rồi ack.

## 6. Yêu cầu với order-service và các service khác

1. **Header AMQP `type` phải bằng `eventType`** trong envelope. Service đọc header trước, fallback sang
   body nếu thiếu; nếu hai giá trị lệch nhau, message bị từ chối và vào DLQ.
2. **Mỗi command phải có `eventId` duy nhất.** Khi retry/gửi lại, dùng đúng `eventId` cũ để consumer bỏ qua
   bản trùng. Đổi `eventId` cho cùng một command sẽ bị xử lý như một command mới.
3. **Commit khi không tìm thấy reservation** (hoặc `orderId` lệch `reservationId`): order-service sẽ nhận
   `inventory.stock-commit-failed` với `reason = RESERVATION_EXPIRED` và `reservationId` lấy từ command.
   Order-service phải coi đây là thất bại và **hoàn tiền** cho khách.
4. **Reserve trùng `orderId`** (reservation còn `RESERVED`): service phát lại `stock-reserved` với
   `reservationId` cũ và `expiresAt` cũ, không trừ kho thêm. Mỗi lần phát là một `eventId` mới.
   Nếu reservation đã `COMMITTED`/`RELEASED`/`EXPIRED`, service log WARN + ack, **không phát event** —
   order-service không nên chờ event trong trường hợp này.
5. **Release không tìm thấy reservation**: service log WARN + ack, **không phát event**. Order-service không
   được chờ `stock-released` cho trường hợp đó.
6. Event dùng thời gian ISO-8601 UTC với đúng 3 chữ số mili giây và hậu tố `Z`
   (ví dụ `2026-10-24T09:30:00.123Z`).

## 7. Quyết định và giả định đã chốt

### 7.1. Các điểm contract từng mơ hồ

| # | Quyết định |
|---|---|
| 1 | Reserve lại cùng `orderId` khi reservation đã `COMMITTED`/`RELEASED`/`EXPIRED`: log WARN + ack, không phát event. |
| 2 | Commit không tìm thấy reservation (hoặc `orderId` lệch): phát `stock-commit-failed` reason `RESERVATION_EXPIRED`, `reservationId` lấy từ command, không đổi kho. Release không tìm thấy: log WARN + ack. |
| 3 | Tra reservation theo `reservationId`; `orderId` lệch được coi như không tìm thấy. |
| 4 | Phân loại command theo AMQP header `type`; thiếu thì dùng `eventType` trong body; lệch nhau hoặc thiếu cả hai thì reject. |
| 5 | `eventVersion = 1` cho cả 5 event. |
| 6 | `CONCURRENT_MODIFICATION`: `failedItems` gồm tất cả item của command, `availableQuantity` đọc tại thời điểm thất bại. |
| 7 | `VARIANT_NOT_FOUND`: `sku` lấy từ command, `availableQuantity = 0`. |
| 8 | Reserve thành công dùng `sku` trong `stock_items`; nếu lệch command thì log WARN. |
| 9 | REST batch: `ids` thiếu/rỗng → 400 `VALIDATION_ERROR`; trim khoảng trắng; UUID không phân biệt hoa/thường; giữ thứ tự xuất hiện đầu tiên, loại trùng; `missingIds` giữ thứ tự xuất hiện. *(Giai đoạn 3)* |
| 10 | Lỗi validation dùng `type = https://malefashion.local/errors/validation-error`, `code = VALIDATION_ERROR`. *(Giai đoạn 3)* |
| 11 | Thiếu `X-Trace-Id` → sinh 32 ký tự hex lowercase, không trả 400. |
| 12 | `Instant` luôn serialize theo `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` UTC. |
| 13 | Phát lại `stock-reserved`: `eventId` mới, giữ `expiresAt` cũ, không gia hạn TTL. |
| 14 | Đường dẫn seed resolve theo working directory; override bằng `inventory.seed.path`. *(Giai đoạn 3)* |
| 15 | Job TTL chỉ phát `stock-released` reason `RESERVATION_EXPIRED`, không phát `stock-commit-failed`. |

### 7.2. Quyết định bổ sung trong Giai đoạn 2

1. **Outbox chỉ SENT sau confirm:** bật `mandatory` + `publisher-returns`; confirm nack, `basic.return`
   hoặc exception đều tính là publish thất bại (giữ `PENDING`, tăng `attempts`).
2. **`messageId` AMQP = `eventId` = khóa chính dòng `outbox_events`.** Không dùng id riêng cho outbox.
3. **Cơ chế khóa: Phương án A** — giữ khóa bi quan có thứ tự `FOR UPDATE` theo `variantId`, kèm `@Version`
   và CHECK DB; không thêm công tắc/chiến lược khóa khác.
4. **Retry optimistic lock nằm ngoài transaction:** listener và `ReserveStockService` không có
   `@Transactional`; mỗi lần thử do `TransactionTemplate` mở transaction mới; container listener không bật
   `channelTransacted`.

### 7.3. Giả định khác

- Nhiều dòng cùng `variantId` trong một command reserve được **cộng dồn** thành một dòng.
- Lỗi hỗn hợp (vừa thiếu variant vừa thiếu hàng): ưu tiên phát `VARIANT_NOT_FOUND` và chỉ liệt kê item lỗi.
- Release reservation `RESERVED` nhưng đã quá `expiresAt` vẫn nhả kho với reason từ command (không tự đổi
  thành `RESERVATION_EXPIRED`); job TTL sẽ bỏ qua vì status đã `RELEASED`.
- Replay `stock-committed` dùng `updatedAt` của reservation làm `committedAt` (bảng không có cột riêng).
- Mọi `Instant` được cắt về mili giây trước khi lưu/so sánh để DB và JSON contract khớp nhau.
- `processed_messages.consumer = "inventory-service"` (cấu hình `inventory.messaging.consumer-name`).
- Test đồng thời gọi trực tiếp `ReserveStockService` để 20 thread bắt đầu cùng lúc; vẫn dùng PostgreSQL thật.

## 8. Hạn chế đã biết

- **Thứ tự publish không tuyệt đối:** một event lỗi trong batch outbox **không chặn** các event sau; event
  lỗi được thử lại ở chu kỳ sau, nên thứ tự giữa nó và các event sau có thể bị đảo. Consumer phải chịu
  được event đến không theo thứ tự.
- Outbox publisher giữ khóa dòng trong lúc chờ broker confirm; broker chậm sẽ làm chậm cả batch.
- Nếu broker đã confirm nhưng bước cập nhật `SENT` trong DB thất bại, event có thể được publish lại
  (at-least-once). Consumer bắt buộc idempotent.
- Job hết hạn dừng lượt chạy khi reservation kế tiếp đang bị instance khác khóa; reservation đó có thể
  chậm tối đa một chu kỳ (60 giây). Mỗi lượt xử lý tối đa 100 reservation.
- DLQ chưa có công cụ replay tự động; cần xem và xử lý thủ công qua Management UI.
- REST và seed loader chưa có (Giai đoạn 3).
- Service không verify JWT; tin thông tin danh tính do API gateway truyền vào theo quy ước của nhóm.
