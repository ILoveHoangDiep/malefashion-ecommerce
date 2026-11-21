package com.malefashion.order.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Khởi động PostgreSQL 16 thật bằng Testcontainers và dọn dữ liệu trước mỗi test.
 * Base này không có RabbitMQ nên tắt listener, outbox publisher và Rabbit health indicator để
 * không có kết nối broker nào bị gọi.
 */
@SpringBootTest
// Mỗi lớp test có context riêng để trạng thái DB không rò rỉ giữa các lớp.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("order_db")
                    .withUsername("postgres")
                    .withPassword("postgrespassword");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("order.messaging.listener.auto-startup", () -> "false");
        registry.add("order.outbox.publisher.enabled", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
        registry.add("order.internal-api-key", () -> "test-internal-api-key");
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        // order_items và checkout_idempotency tham chiếu orders nên phải truncate cùng lúc.
        jdbcTemplate.execute("TRUNCATE TABLE order_items, checkout_idempotency, "
                + "outbox_events, processed_messages, orders");
    }
}
