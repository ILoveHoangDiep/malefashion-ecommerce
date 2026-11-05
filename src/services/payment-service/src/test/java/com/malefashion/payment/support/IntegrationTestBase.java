package com.malefashion.payment.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.payment.infrastructure.persistence.OutboxEventRepository;
import com.malefashion.payment.infrastructure.persistence.PaymentRepository;
import com.malefashion.payment.infrastructure.persistence.ProcessedMessageRepository;
import com.malefashion.payment.infrastructure.persistence.RefundRepository;
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
 * Prompt 1 chưa nối RabbitMQ nên base này chỉ cần database.
 */
@SpringBootTest
// Mỗi lớp test có context riêng để trạng thái DB không rò rỉ giữa các lớp.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("payment_db")
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
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected PaymentRepository paymentRepository;

    @Autowired
    protected RefundRepository refundRepository;

    @Autowired
    protected OutboxEventRepository outboxEventRepository;

    @Autowired
    protected ProcessedMessageRepository processedMessageRepository;

    @BeforeEach
    void resetState() {
        // refunds tham chiếu payments nên phải truncate cùng lúc.
        jdbcTemplate.execute("TRUNCATE TABLE refunds, payments, outbox_events, processed_messages");
    }
}
