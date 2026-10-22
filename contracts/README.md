# malefashion contracts


## File

- `events/common.schema.json`: Uuid, Money, Timestamp, OrderNumber, Sku.
- `events/envelope.schema.json`: envelope chung cho event và command.
- `events/{order,inventory,payment}-*.json`: 11 event + 5 command; mỗi file có schema payload và `examples`.
- `openapi/order-api.yaml`: checkout, chi tiết đơn, lịch sử đơn.
- `openapi/cart-coupon-api.yaml`: `GET /internal/v1/carts/{userId}`.
- `openapi/catalog-api.yaml`: `POST /internal/v1/variants/batch`.
- `openapi/inventory-api.yaml`: đọc tồn kho public.
- `openapi/identity-api.yaml`: JWT claims bắt buộc + JWKS; login/register do C tự định nghĩa.
- `openapi/common.yaml`: Money, ProblemDetail, PageResponse, header parameters dùng chung.
- `seed/variants.json`: 60 variant dùng chung cho catalog, inventory và E2E.
- `../deploy/rabbitmq/definitions.json`: exchange, queue, binding, DLQ.

## Quy ước chung

- Tiền: `{"amount": "299000.00", "currency": "VND"}`; `amount` là string decimal 2 chữ số.
- ID: UUID lowercase 36 ký tự. `orderNumber`: `MF-YYYYMMDD-XXXXXX`.
- Thời gian: ISO-8601 UTC, milliseconds, hậu tố `Z`. Mọi service chạy UTC.
- JSON: camelCase; bỏ field null; bỏ qua field lạ khi deserialize.
- Enum: UPPER_SNAKE_CASE. Lỗi: RFC 9457 `application/problem+json`, có `code`, `traceId`, `timestamp`; validation có `errors[]`.
- Pagination: `page` 0-based, `size` mặc định 20 / tối đa 100; response `content,page,size,totalElements,totalPages`.
- Checkout bất đồng bộ: trả `PENDING`; storefront poll `GET /api/v1/orders/{orderId}` mỗi 2s, UI timeout 60s. SignalR ngoài phạm vi v0.1.
- `shippingFee` cố định `30000.00 VND` do order-service tính; cart không gồm phí này.

## Danh tính

- JWT RS256 do identity-service phát, bắt buộc claim `sub` (userId UUID), `email`, `roles`.
- Gateway strip `X-User-*`, `X-Internal-*`, `X-Trace-Id`, `traceparent` từ client, rồi inject:
  `X-User-Id`, `X-User-Email`, `X-User-Roles` (comma-separated, uppercase), `X-Trace-Id` (32 hex).
- Gọi `/internal/**`: thêm `X-Internal-Api-Key`, `X-Caller-Service`; gateway block `/internal/**`.
- Không log `X-User-Email`, `Authorization`, `X-Internal-Api-Key`.

## Gateway và port

Route gateway (từ ngoài vào):

| Public path | Service đích |
|---|---|
| `/api/v1/auth/**` | identity-service:8081 |
| `/api/v1/users/**` | identity-service:8081 |
| `/api/v1/products/**` | catalog-service:8082 |
| `/api/v1/categories/**` | catalog-service:8082 |
| `/api/v1/cart/**` | cart-coupon-service:8083 |
| `/api/v1/coupons/**` | cart-coupon-service:8083 |
| `/api/v1/orders/**` | order-service:8084 |
| `/api/v1/inventory/**` | inventory-service:8085 |
| `/api/v1/payments/**` | payment-service:8086 |
| `/api/v1/notifications/**` | notification-service:8087 |
| `/internal/**` | **block** (không route từ ngoài) |
| `/actuator/**` | **block** (chỉ healthcheck nội bộ) |

`/hubs/notifications` ngoài phạm vi v0.1.

| Service | Port nội bộ | Publish host |
|---|---|---|
| api-gateway | 8080 | 8080 |
| identity-service | 8081 | không |
| catalog-service | 8082 | không |
| cart-coupon-service | 8083 | không |
| order-service | 8084 | không |
| inventory-service | 8085 | không |
| payment-service | 8086 | không |
| notification-service | 8087 | không |
| storefront | 3000 | 3000 |

## RabbitMQ definitions

Chưa sửa `docker-compose.infra.yml`. C mount file và nạp definitions khi khởi động:

```yaml
rabbitmq:
  volumes:
    - ./deploy/rabbitmq/definitions.json:/etc/rabbitmq/definitions.json:ro
  environment:
    RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS: '-rabbitmq_management load_definitions "/etc/rabbitmq/definitions.json"'
```

Cách khác: `curl -u guest:guest -X POST -H 'content-type: application/json' --data-binary @deploy/rabbitmq/definitions.json http://localhost:15672/api/definitions`.

Service tắt auto-declare; chỉ publish/consume. Queue chính có DLX `malefashion.dlx`, routing key `<queue>.dlq`.

## Seed

Sinh lại (xác định, chạy lại ra y hệt): `python scripts/generate-seed-variants.py`.
Catalog bỏ qua `initialStock`; inventory dùng `initialStock` để tạo tồn kho.

## Đổi contract

Mọi thay đổi file trong `contracts/` phải báo nhóm chat trước và cập nhật tất cả phía liên quan trong cùng ngày. Thay đổi breaking phải tạo `eventType` mới (`.v2`), không sửa/xóa field của version cũ.
