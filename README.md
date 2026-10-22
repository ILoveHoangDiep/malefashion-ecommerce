## Khởi Chạy Hạ Tầng Cục Bộ (Local Infrastructure)

Cụm hạ tầng dùng chung được đóng gói qua Docker Compose gồm: **PostgreSQL (7 logical databases)**, **Redis**, **RabbitMQ**, **MinIO (S3)** và **MailHog (SMTP)**.

---

### Khi bắt đầu làm việc
* Khởi động **Docker Desktop**, đợi vài giây để running
* **Kiểm tra trùng cổng:** Đảm bảo không có dịch vụ PostgreSQL (`5432`) hoặc Redis (`6379`) cài sẵn trên Windows đang chạy ngầm (mở `services.msc`, tìm và tạm thời **Stop**).
* Tại thư mục gốc của repo, mở terminal và chạy lệnh: 
    **docker compose -f deploy/docker-compose/docker-compose.infra.yml up -d**
* Kiểm tra trạng thái các container: chạy lệnh **docker ps**. toàn bộ các container hiển thị trạng thái up/healthy -> ok. nếu error -> hỏi AI :>

## Khi dừng
 **docker compose -f deploy/docker-compose/docker-compose.infra.yml stop**
 hoặc **docker compose -f deploy/docker-compose/docker-compose.infra.yml down** nếu muốn dọn cổng