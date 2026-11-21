# order-service

Order-service là **Saga Orchestrator** của luồng checkout: tạo đơn, điều phối inventory-service và
payment-service qua RabbitMQ, và ghi event bằng **Transactional Outbox**. Service chạy cổng `8084`,
dùng PostgreSQL `order_db`, mọi thời gian theo UTC.

> README đầy đủ (chạy local, smoke test, Docker, thiết kế) sẽ được hoàn thiện ở giai đoạn 5.
> Hiện tại mới có khung dự án, schema DB và state machine thuần.

## Trạng thái triển khai

- [x] Giai đoạn 1: khung dự án, Flyway schema, domain state machine, unit + integration test nền.
- [ ] Giai đoạn 2a: outbox publisher, listener, RabbitConfig, event factory.
- [ ] Giai đoạn 2b: handler saga.
- [ ] Giai đoạn 3: REST checkout/đọc đơn.
- [ ] Giai đoạn 4: timeout job + E2E RabbitMQ.
- [ ] Giai đoạn 5: smoke test, README đầy đủ, Dockerfile.

## Hạn chế đã biết

### Refund retry

Retry dùng cùng eventId chỉ đảm bảo **LỆNH KHÔNG BỊ MẤT** (nếu lần gửi trước chưa tới
payment-service). payment-service dedupe theo eventId: nếu lệnh đã được xử lý rồi thì bản gửi lại bị
bỏ qua và **KHÔNG có event mới**; kết quả (`payment.refunded`) đã nằm trong outbox của payment-service
từ lần xử lý đầu và sẽ tới order-service theo cơ chế at-least-once. Vì vậy retry **KHÔNG phải cách để
lấy lại một event đã mất**. Nếu hết lượt retry vẫn không thấy `payment.refunded` →
`refund_state=REFUND_FAILED`, cần xử lý thủ công (kiểm tra bảng `payments`/`refunds` và DLQ).
