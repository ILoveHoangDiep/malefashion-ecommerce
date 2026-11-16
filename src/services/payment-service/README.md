# payment-service

Service thanh toán **mock** của hệ thống **malefashion**. Service nhận command của saga từ
`order-service` qua RabbitMQ, ghi kết quả thanh toán vào PostgreSQL, rồi phát event để saga tiếp tục.
Không gọi cổng thanh toán thật: kết quả được quyết định bởi `mockScenario` (chỉ ở profile local/demo).
Mọi event được ghi vào **outbox** trong cùng transaction với nghiệp vụ, sau đó mới publish lên broker;
consumer xử lý idempotent theo `eventId`.

- Java 21, Spring Boot 3.5.16, PostgreSQL 16, RabbitMQ 3.
- Cổng mặc định: **8086**. Múi giờ: **UTC**.
- Không có REST nghiệp vụ; chỉ mở `GET /actuator/health`.

## 1. Luồng xử lý

```text
order-service
   │  publish command (routing key = payment.process-payment / payment.refund-payment)
   ▼
exchange malefashion.commands (topic)
   ▼
queue payment-service.commands
   ▼
PaymentCommandListener
   ▼
PaymentCommandProcessor — MỘT transaction:
   ├─ INSERT processed_messages (consumer, message_id = eventId)  → trùng thì ack & bỏ qua
   ├─ ProcessPaymentService / RefundPaymentService ghi payments, refunds
   └─ INSERT outbox_events (envelope JSON đầy đủ)
   ▼
OutboxPublisher (mỗi 500 ms)
   ▼  publisher confirm + mandatory/returns, chỉ SENT sau khi broker ack
exchange malefashion.events (routing key = eventType)
   ▼
queue order-service.saga.events
   ▼
order-service
```

`process-payment` tạo đúng **một payment cho mỗi `orderId`**. Khi order-service gửi lại cùng `orderId`,
service không tạo payment mới mà phát lại kết quả cũ với **`eventId` mới**. `refund-payment` hoàn tiền
toàn phần và cũng phát lại cùng `refundId` nếu nhận lệnh lần hai.

## 2. Message nhận / phát

### 2.1. Command service nhận

Tất cả command đi vào exchange `malefashion.commands` và queue `payment-service.commands`.
Service **không tự khai báo** exchange/queue/binding; topology do `deploy/rabbitmq/definitions.json` quản lý.

| Command (routing key) | Queue | Khi nào order-service gửi | Kết quả |
|---|---|---|---|
| `payment.process-payment` | `payment-service.commands` | Sau khi tạo đơn, cần thanh toán | `payment.succeeded` hoặc `payment.failed` |
| `payment.refund-payment` | `payment-service.commands` | Đã thanh toán nhưng `inventory.stock-commit-failed` | `payment.refunded` (hoặc không event nếu không tìm thấy payment) |

Command phải là envelope JSON có `eventId`, `eventType`, `eventVersion`, `occurredAt`, `correlationId`,
`payload`. Header AMQP `type` phải bằng `eventType` (nếu thiếu, service đọc `eventType` trong body; nếu
hai giá trị lệch nhau, message bị từ chối).

`payment.process-payment` payload:

| Field | Bắt buộc | Ghi chú |
|---|---|---|
| `orderId` | có | UUID |
| `amount` | có | `{ "amount": "578000.00", "currency": "VND" }`, tối đa 2 chữ số thập phân, > 0 |
| `idempotencyKey` | có | Không rỗng; hiện order-service dùng chính `orderId` |
| `mockScenario` | không | `SUCCESS`, `FAILURE`, `TIMEOUT`; chỉ dùng khi `payment.mock.honor-scenario=true` |

`payment.refund-payment` payload:

| Field | Bắt buộc | Ghi chú |
|---|---|---|
| `orderId` | có | UUID |
| `paymentId` | có | UUID lấy từ `payment.succeeded` |
| `amount` | có | Số tiền hoàn; service luôn hoàn đúng số đã thanh toán |
| `reason` | có | Chỉ chấp nhận `STOCK_COMMIT_FAILED` |

`correlationId` của command phải là `orderId`; event phát ra giữ nguyên giá trị này.

### 2.2. Event service phát

Tất cả event đi vào exchange `malefashion.events`, routing key = `eventType`, và được bind tới queue
`order-service.saga.events` theo `definitions.json`.

| Event (routing key) | Khi nào | Payload chính |
|---|---|---|
| `payment.succeeded` | Thanh toán mock thành công, hoặc phát lại cho cùng `orderId` | `orderId`, `paymentId`, `amount`, `providerTransactionId`, `paidAt` |
| `payment.failed` | Thanh toán mock thất bại (`PAYMENT_DECLINED` / `PAYMENT_TIMEOUT`) | `orderId`, `paymentId`, `amount`, `failureCode`, `failureMessage`, `failedAt` |
| `payment.refunded` | Hoàn tiền thành công, hoặc phát lại cho payment đã REFUNDED | `orderId`, `paymentId`, `refundId`, `amount`, `refundedAt` |

- `providerTransactionId` có dạng `MOCK-TXN-yyyyMMdd-NNNNNN` (ví dụ `MOCK-TXN-20261105-000001`), sinh từ
  sequence PostgreSQL và unique toàn bảng.
- `PAYMENT_DECLINED` ↔ `mockScenario=FAILURE`; `PAYMENT_TIMEOUT` ↔ `mockScenario=TIMEOUT`.
- AMQP properties của event: `messageId = eventId` (cũng là khóa chính dòng `outbox_events`),
  `type = eventType`, `correlationId = orderId`, `contentType = application/json`, delivery mode
  `persistent`, header `x-event-version`.
- Mọi event dùng `eventId` **mới**, kể cả khi phát lại kết quả cũ; `occurredAt` và các timestamp trong
  payload là ISO-8601 UTC với đúng 3 chữ số mili giây và hậu tố `Z`.

## 3. Chạy local bằng PowerShell

### 3.1. Bật hạ tầng

Từ thư mục gốc repo:

```powershell
cd D:\malefashion-ecommerce
docker compose -f deploy\docker-compose\docker-compose.infra.yml up -d
docker compose -f deploy\docker-compose\docker-compose.infra.yml ps
```

Chờ `malefashion-postgres` và `malefashion-rabbitmq` ở trạng thái healthy/running. Script khởi tạo
PostgreSQL tạo sẵn database `payment_db` (nằm trong `POSTGRES_MULTIPLE_DATABASES`).

Nếu cổng 5432 trên máy đã bị một PostgreSQL khác chiếm, dùng override có sẵn của inventory-service để
publish PostgreSQL ra cổng 15432:

```powershell
docker compose -f deploy\docker-compose\docker-compose.infra.yml `
               -f src\services\inventory-service\scripts\docker-compose.local-db-port.yml `
               up -d postgres
$env:POSTGRES_PORT = "15432"    # dùng cho cả lúc chạy service
```

Nếu log `malefashion-postgres` báo `/bin/bash^M: bad interpreter` (script `scripts/init-multiple-dbs.sh` ở
thư mục gốc repo đang dùng CRLF), container không tạo được database. Tạo thủ công rồi khởi động lại:

```powershell
docker start malefashion-postgres
docker exec malefashion-postgres psql -U postgres -c "CREATE DATABASE payment_db;"
```

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

Kiểm tra nhanh trong Management UI: có queue `payment-service.commands`, DLQ
`payment-service.commands.dlq`, exchange `malefashion.commands`, `malefashion.events`,
`malefashion.dlx`; ba binding `payment.succeeded` / `payment.failed` / `payment.refunded` trên
`malefashion.events` trỏ tới `order-service.saga.events`.

### 3.3. Chạy service

```powershell
cd D:\malefashion-ecommerce\src\services\payment-service
$env:SPRING_PROFILES_ACTIVE = "local"    # bật log DEBUG + honor mockScenario
.\mvnw.cmd spring-boot:run
```

Service lắng nghe ở <http://localhost:8086>; kiểm tra <http://localhost:8086/actuator/health>.
Không chạy `clean` khi service đang chạy trên Windows (Maven sẽ xóa `target\` và gây lỗi file bị khóa).

Service dùng các biến môi trường sau (đều có default cho máy local):

| Biến | Default | Ý nghĩa |
|---|---|---|
| `PAYMENT_SERVICE_PORT` | `8086` | Cổng HTTP |
| `POSTGRES_HOST` / `POSTGRES_PORT` | `localhost` / `5432` | PostgreSQL |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `postgres` / `postgrespassword` | Tài khoản DB (compose local đặt sẵn) |
| `PAYMENT_DB_NAME` | `payment_db` | Tên database |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | RabbitMQ AMQP |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `guest` / `guest` | Tài khoản RabbitMQ (production phải đổi) |
| `RABBITMQ_VHOST` | `/` | Virtual host |
| `PAYMENT_OUTBOX_PUBLISH_INTERVAL_MS` | `500` | Chu kỳ outbox publisher |
| `PAYMENT_OUTBOX_PUBLISHER_ENABLED` | `true` | Công tắc bật/tắt `OutboxPublisher` (bean không tồn tại khi `false`) |
| `PAYMENT_MESSAGING_LISTENER_AUTO_STARTUP` | `true` | Công tắc start listener khi khởi động |
| `PAYMENT_MESSAGE_MAX_ATTEMPTS` | `3` | Tổng số lần xử lý message trước khi vào DLQ |
| `PAYMENT_MOCK_HONOR_SCENARIO` | `false` (local/demo: `true`) | Cho phép đọc `mockScenario` từ command |
| `PAYMENT_MOCK_SILENT_ON_TIMEOUT` | `false` | Không phát `payment.failed` cho `PAYMENT_TIMEOUT` (chỉ để demo saga timeout) |

Hai công tắc `PAYMENT_OUTBOX_PUBLISHER_ENABLED` và `PAYMENT_MESSAGING_LISTENER_AUTO_STARTUP` tồn tại để
test chỉ có PostgreSQL không chạm broker thật. Khi chạy service bình thường, để nguyên default `true`.

Không commit file chứa mật khẩu thật; khi chạy thật hãy đặt biến môi trường hoặc dùng secret manager.

### 3.4. Smoke test end-to-end

`scripts\smoke-test.ps1` kiểm tra service đang chạy thật (không phải Testcontainers): tạo một queue tạm
bind `malefashion.events` với routing key `payment.*`, publish command qua RabbitMQ Management API vào
`malefashion.commands`, rồi đối chiếu event nhận được.

Điều kiện: hạ tầng đã bật, đã nạp `definitions.json`, service đang chạy với profile `local` (để
`mockScenario` có tác dụng). Script tự xoá queue tạm và dọn `payment-service.commands.dlq` ở cuối, kể cả
khi có bước FAIL. Mỗi lần chạy dùng `orderId`/`eventId` mới nên chạy lặp lại được.

```powershell
cd D:\malefashion-ecommerce\src\services\payment-service
.\scripts\smoke-test.ps1
```

Kịch bản:

| Bước | Kiểm tra |
|---|---|
| (a) | `GET /actuator/health` trả `UP` |
| (b) | `process-payment` với `mockScenario=SUCCESS` → `payment.succeeded`, đúng amount và `providerTransactionId` đúng pattern |
| (c) | gửi lại **cùng `eventId`** → không có event mới |
| (d) | `process-payment` với `mockScenario=FAILURE` (order khác) → `payment.failed` `PAYMENT_DECLINED` |
| (e) | `process-payment` với `mockScenario=TIMEOUT` (order khác) → `payment.failed` `PAYMENT_TIMEOUT` |
| (f) | `refund-payment` cho payment ở (b) → `payment.refunded` |
| (g) | refund lần hai với `eventId` mới → `payment.refunded` cùng `refundId` |
| (h) | refund cho `orderId` không tồn tại → không có event trong thời gian chờ |
| (i) | publish JSON hỏng → message nằm trong `payment-service.commands.dlq` |

Script in `[PASS]`/`[FAIL]` cho từng bước và tổng kết `PASS: n  FAIL: m`; thoát mã **1** nếu có FAIL.

Tham số (đều có default): `-BaseUrl http://localhost:8086`, `-ManagementUrl http://localhost:15672`,
`-RabbitUser guest`, `-RabbitPassword guest`, `-Vhost /`, `-WaitSeconds 20`.

### 3.5. Build và chạy Docker (tuỳ chọn)

```powershell
# build context là thư mục service
docker build -t malefashion/payment-service:dev src/services/payment-service
```

- Build nhiều tầng: `maven:3.9-eclipse-temurin-21-alpine` chạy `./mvnw -B -DskipTests package`
  (**test không chạy trong image** — chạy `.\mvnw.cmd clean verify` ở máy trước khi build), runtime
  `eclipse-temurin:21-jre-alpine`. Container chạy bằng user `app` (uid 100), không phải root, expose 8086.
- `contracts/` **không** được copy vào image; service không cần file contract lúc chạy.

Ví dụ chạy container nối vào PostgreSQL/RabbitMQ của compose trên máy host:

```powershell
docker run -d --name payment-service -p 8086:8086 `
  -e POSTGRES_HOST=host.docker.internal -e POSTGRES_PORT=5432 `
  -e RABBITMQ_HOST=host.docker.internal -e RABBITMQ_PORT=5672 `
  -e RABBITMQ_USER=<user> -e RABBITMQ_PASSWORD=<password> `
  malefashion/payment-service:dev

docker exec payment-service id     # uid=100(app) ...
curl http://localhost:8086/actuator/health
```

- `host.docker.internal` để container gọi DB/broker trên máy host; nếu chạy chung compose network thì dùng
  tên service (`postgres`, `rabbitmq`).
- Tài khoản RabbitMQ `guest` chỉ đăng nhập được từ localhost; container kết nối qua bridge sẽ bị từ chối.
  Tạo user riêng cho container (quyền trên vhost `/`) hoặc cấu hình `loopback_users` cho môi trường dev.
- Nếu PostgreSQL publish ở cổng 15432 (xem 3.1) thì đặt `-e POSTGRES_PORT=15432`.
- Container chạy profile mặc định, nên `mockScenario` bị bỏ qua và mọi payment đều `SUCCESS`. Muốn demo
  FAILURE/TIMEOUT trong container, thêm `-e SPRING_PROFILES_ACTIVE=local`.

## 4. Chạy test bằng PowerShell

Yêu cầu **Docker Desktop đang chạy**: test tích hợp dùng Testcontainers thật
(`postgres:16-alpine`, `rabbitmq:3-management-alpine`), không dùng H2 hay mock DB/broker.

```powershell
cd D:\malefashion-ecommerce\src\services\payment-service

# Toàn bộ unit + integration test
.\mvnw.cmd clean verify

# Chỉ unit test
.\mvnw.cmd test

# Một lớp test bất kỳ
.\mvnw.cmd -Dit.test=PaymentCommandFlowIT verify
```

Hiện có **16 unit test** (surefire) và **50 integration test** (failsafe):

| Nhóm | Lớp test |
|---|---|
| Unit | `MockScenarioResolverTest` (6), `ProviderTransactionIdGeneratorTest` (3), `PaymentEventFactoryTest` (7) |
| Nghiệp vụ + PostgreSQL | `ProcessPaymentServiceIT` (9), `RefundPaymentServiceIT` (8), `ProcessPaymentSilentTimeoutIT` (3), `ProcessPaymentConcurrencyIT` (1), `PaymentSchemaConstraintIT` (5) |
| Context / cấu hình | `PaymentServiceContextIT` (3), `PaymentTestIsolationIT` (1) |
| RabbitMQ end-to-end | `PaymentCommandFlowIT` (8), `PaymentDlqIT` (6), `PaymentOutboxPublisherIT` (4), `PaymentContractExamplesIT` (2) |

- Mỗi lớp test tích hợp tự dựng PostgreSQL (và RabbitMQ nếu cần) riêng, nạp
  `deploy/rabbitmq/definitions.json` qua Management API và dọn dữ liệu trước mỗi test.
- `IntegrationTestBase` tắt listener + outbox publisher cho test chỉ có PostgreSQL, nên test cũ không
  consume command hay publish event vào broker thật trên máy dev. `MessagingIntegrationTestBase` bật lại
  hai công tắc này sau khi đã trỏ `spring.rabbitmq.port` vào container.
- Mọi payload trong `outbox_events` được validate bằng JSON Schema đọc trực tiếp từ `contracts/events`.
- Lần chạy đầu sẽ kéo Docker image và Maven dependency, chậm hơn các lần sau.

## 5. Thiết kế

### 5.1. Một payment cho mỗi order

- `payments.order_id` có UNIQUE constraint. `ProcessPaymentService` dùng
  `INSERT ... ON CONFLICT (order_id) DO NOTHING`; nếu order đã có payment, service đọc lại dòng đã thắng
  thay vì tạo bản ghi mới.
- Hai command `process-payment` chạy song song cho cùng `orderId` chỉ tạo một payment; command thua đọc
  payment của command thắng và phát lại kết quả.
- Khi phát lại, service dùng dữ liệu **đã lưu** (paymentId, amount, providerTransactionId, failureCode,
  thời điểm) và sinh `eventId` mới. Nếu command mới mang amount khác, service log WARN và giữ dữ liệu cũ.
- Payment đã `REFUNDED`: `process-payment` đến muộn bị bỏ qua, log WARN, **không phát event**, để không
  đẩy order về trạng thái đã thanh toán sau khi đã hoàn tiền.

### 5.2. Transactional outbox

- Trong cùng transaction với thay đổi `payments`/`refunds`, service ghi event vào bảng `outbox_events`
  (payload là envelope JSON đầy đủ). Nếu transaction rollback, event cũng không tồn tại.
- `OutboxPublisher` chạy mỗi **500 ms**, lấy tối đa **100** dòng `PENDING` bằng `FOR UPDATE SKIP LOCKED`
  (nhiều instance không publish trùng một dòng).
- Publish lên `malefashion.events` với routing key = `eventType`, bật **publisher confirms**.
- Chỉ đánh dấu `SENT` và ghi `published_at` **sau khi broker ack**. Confirm nack,
  `basic.return` do không có binding, hoặc exception đều tính là thất bại: tăng `attempts`, giữ `PENDING`
  để chu kỳ sau gửi lại.
- Kết quả là mô hình **at-least-once**: event có thể được gửi hơn một lần, nên consumer phía sau phải
  idempotent theo `eventId`.

### 5.3. Consumer idempotent

- Bảng `processed_messages(consumer, message_id)` với khóa chính `(consumer, message_id)`;
  `consumer = "payment-service"`.
- Trong transaction xử lý command, service chạy
  `INSERT ... ON CONFLICT (consumer, message_id) DO NOTHING` **trước** nghiệp vụ. Nếu không chèn được
  (message đã xử lý), service **ack và bỏ qua**, không chạy lại nghiệp vụ.
- `message_id` lấy từ `eventId` của envelope, nên **mỗi command phải có `eventId` duy nhất**; khi retry,
  order-service phải gửi lại đúng `eventId` cũ.
- Nếu nghiệp vụ ném lỗi, cả marker lẫn thay đổi payment/outbox rollback cùng nhau; message được retry
  hoặc vào DLQ.

### 5.4. Khóa dòng khi refund

- `RefundPaymentService` đọc payment bằng `SELECT ... FOR UPDATE` theo `orderId`, nên hai lệnh refund
  song song cho cùng order phải chờ nhau; lệnh thứ hai thấy payment đã `REFUNDED` và phát lại cùng
  `refundId`.
- `refunds.payment_id` và `refunds.order_id` đều UNIQUE, là lớp chặn cuối cho trường hợp code có lỗi.
- Refund, trạng thái `REFUNDED` của payment và event `payment.refunded` nằm trong cùng transaction.
- Service chỉ hoàn tiền **toàn phần**, đúng số đã thanh toán; nếu command gửi amount khác, service log
  WARN và hoàn theo số đã thanh toán.

### 5.5. Retry và DLQ

- Listener retry **3 lần tổng cộng** (1 lần đầu + 2 lần thử lại), backoff `500 ms × 2`, tối đa `5 s`.
- Quá 3 lần: message bị reject **không requeue** → `malefashion.dlx` → `payment-service.commands.dlq`.
- Lỗi vĩnh viễn (JSON hỏng, thiếu field bắt buộc, `type` header lệch `eventType`, command không hỗ trợ,
  payload sai validation) bị `AmqpRejectAndDontRequeueException` ngay, **không retry**, đi thẳng DLQ.
- Kết quả nghiệp vụ bình thường (declined, timeout, không tìm thấy payment để refund, replay) đều được
  **ack**, không retry.

### 5.6. Mock scenario

- `mockScenario` chỉ được đọc khi `payment.mock.honor-scenario=true` (profile `local` và `demo` bật sẵn;
  mặc định là `false`). Khi tắt cờ, mọi payment đều `SUCCESS`, bất kể field trong command.
- Thiếu field hoặc giá trị lạ → coi như `SUCCESS` và log WARN. Điều này giữ cho luồng của order-service
  không bị kẹt vì dữ liệu mock sai.
- `payment.mock.silent-on-timeout=true` khiến payment `FAILED` với `PAYMENT_TIMEOUT` **không phát event**
  (cả lần đầu lẫn replay). Chỉ dùng để demo nhánh saga timeout; mặc định `false`.
- Cả hai cờ chỉ ảnh hưởng hành vi mock, không thay đổi schema hay contract.

## 6. Yêu cầu với order-service

1. **Lưu `paymentId` và `amount` từ `payment.succeeded`** để gửi `payment.refund-payment` khi cần hoàn tiền.
2. **Mỗi command phải có `eventId` duy nhất**; khi retry/gửi lại, dùng đúng `eventId` cũ để consumer bỏ
   qua bản trùng. Header AMQP `type` phải bằng `eventType` trong envelope.
3. **Refund khi payment-service không tìm thấy payment sẽ không có event.** Service log WARN + ack và
   không phát `payment.refunded`; order-service không được chờ event này trong trường hợp đó.
4. **`process-payment` gửi lại cùng `orderId` trả về kết quả cũ với `eventId` mới.** Dùng lại
   `paymentId`/`amount` đã lưu, không tạo payment mới.
5. **`payment.succeeded` có thể đến muộn sau khi order đã bị huỷ vì `SAGA_TIMEOUT`.** Order-service phải
   kiểm tra trạng thái và hoàn tiền nếu payment đã thành công.
6. **Demo saga timeout:** đặt `PAYMENT_MOCK_SILENT_ON_TIMEOUT=true`, khởi động lại payment-service, rồi
   gửi `process-payment` với `mockScenario=TIMEOUT`; payment-service sẽ không phát `payment.failed`.

## 7. Quyết định và giả định đã chốt

### 7.1. Các điểm contract từng mơ hồ

| # | Quyết định |
|---|---|
| 1 | Mỗi `orderId` chỉ có một payment (`payments.order_id` UNIQUE). |
| 2 | `process-payment` gửi lại cùng `orderId`: phát lại kết quả cũ với `eventId` mới, dùng dữ liệu đã lưu. |
| 3 | `process-payment` cho payment đã `REFUNDED`: log WARN + ack, **không phát event**. |
| 4 | `refund-payment` không tìm thấy payment, `paymentId` lệch, hoặc payment `FAILED`: log WARN + ack, **không phát event**. |
| 5 | Refund đã `REFUNDED`: phát lại cùng `refundId`/`refundedAt` với `eventId` mới. |
| 6 | Refund luôn hoàn toàn phần theo số đã thanh toán; amount trong command chỉ để đối chiếu và log WARN nếu lệch. |
| 7 | Chỉ chấp nhận `reason=STOCK_COMMIT_FAILED`; giá trị khác là lỗi validation và đi DLQ. |
| 8 | `mockScenario` thiếu/không hợp lệ → `SUCCESS` + WARN; cờ `honor-scenario=false` → luôn `SUCCESS`. |
| 9 | `silent-on-timeout` chỉ bỏ event `PAYMENT_TIMEOUT`, không ảnh hưởng `PAYMENT_DECLINED`. |
| 10 | Phân loại command theo AMQP header `type`; thiếu thì dùng `eventType` trong body; lệch nhau hoặc thiếu cả hai thì reject. |
| 11 | `eventVersion = 1` cho cả 3 event. |
| 12 | `providerTransactionId` chỉ sinh cho payment `SUCCEEDED`; format `MOCK-TXN-yyyyMMdd-NNNNNN`. |
| 13 | Mọi `Instant` được cắt về mili giây trước khi lưu/phát. |
| 14 | Không có REST nghiệp vụ; chỉ mở health. |

### 7.2. Quyết định bổ sung trong quá trình triển khai

1. **Outbox chỉ SENT sau confirm:** bật `mandatory` + `publisher-returns`; confirm nack, `basic.return`
   hoặc exception đều tính là publish thất bại (giữ `PENDING`, tăng `attempts`).
2. **`messageId` AMQP = `eventId` = khóa chính dòng `outbox_events`.**
3. **Dedupe và nghiệp vụ trong cùng một transaction** do `PaymentCommandProcessor` mở; listener không có
   `@Transactional`.
4. **Hai công tắc vận hành** `payment.outbox.publisher.enabled` và
   `payment.messaging.listener.auto-startup`, default `true`, để test chỉ có PostgreSQL không chạm broker
   thật. `OutboxPublisher` dùng `@ConditionalOnProperty(matchIfMissing = true)`; không class nào inject nó
   nên tắt bean là an toàn.
5. **Cơ chế replay nằm ở tầng nghiệp vụ**, không dựa vào broker: command trùng `orderId` nhưng `eventId`
   mới vẫn chạy qua dedupe rồi phát lại kết quả.
6. **Amount có tối đa 2 chữ số thập phân**; vi phạm là lỗi validation, vào DLQ.

### 7.3. Giả định khác

- `correlationId` của command luôn là `orderId`; event phát ra giữ nguyên `correlationId` đó.
- `traceId` không hợp lệ hoặc thiếu được thay bằng 32 ký tự hex lowercase mới; không làm fail command.
- `scenario` được lưu trên `payments` để debug; production luôn là `SUCCESS`.
- `idempotencyKey` hiện chỉ kiểm tra không rỗng; khóa chống trùng thực tế là `orderId`.
- Payment `FAILED` vẫn có `paymentId` và phát trong `payment.failed` để order-service lưu vết.

## 8. Hạn chế đã biết

- **Đây là mock:** không gọi cổng thanh toán thật, không có retry với provider, không có xác nhận
  chuyển khoản. `TIMEOUT` được mô phỏng bằng một event `PAYMENT_TIMEOUT` gần như tức thời, không phải
  chờ timeout thật.
- **Thứ tự publish không tuyệt đối:** một event lỗi trong batch outbox **không chặn** các event sau;
  event lỗi được thử lại ở chu kỳ sau, nên thứ tự giữa nó và các event sau có thể bị đảo. Consumer phải
  chịu được event đến không theo thứ tự.
- Outbox publisher giữ khóa dòng trong lúc chờ broker confirm; broker chậm sẽ làm chậm cả batch.
- Nếu broker đã confirm nhưng bước cập nhật `SENT` trong DB thất bại, event có thể được publish lại
  (at-least-once). Consumer bắt buộc idempotent.
- DLQ chưa có công cụ replay tự động; cần xem và xử lý thủ công qua Management UI.
- Chỉ hoàn tiền **toàn phần** với lý do `STOCK_COMMIT_FAILED`; chưa hỗ trợ hoàn một phần, hoàn nhiều lần
  cho một payment, hoặc hoàn vì lý do khác.
- Không có API tra cứu payment/refund; muốn kiểm tra phải query PostgreSQL.
- Service không verify JWT và không có endpoint nghiệp vụ; tin thông tin danh tính do API gateway truyền
  vào theo quy ước của nhóm.
- `silent-on-timeout` cố ý để order-service chờ tới `SAGA_TIMEOUT`; không dùng trong production.
- `scripts/smoke-test.ps1` cần RabbitMQ Management API (15672), service chạy profile `local`, và tài khoản
  dev; chỉ dùng cho môi trường local.
