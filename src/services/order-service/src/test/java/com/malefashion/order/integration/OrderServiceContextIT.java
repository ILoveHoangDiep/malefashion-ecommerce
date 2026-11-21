package com.malefashion.order.integration;

import com.malefashion.order.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Context khởi động chỉ với PostgreSQL: không có RabbitMQ, listener và outbox publisher đều tắt.
 */
@AutoConfigureMockMvc
class OrderServiceContextIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void appliesFlywayMigration() {
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                Integer.class);

        assertThat(versions).containsExactly(1);
    }

    @Test
    void runsOnPostgres16() {
        String version = jdbcTemplate.queryForObject("SELECT version()", String.class);

        assertThat(version).contains("PostgreSQL 16");
    }

    @Test
    void createsAllExpectedTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("orders", "order_items", "checkout_idempotency",
                "outbox_events", "processed_messages", "flyway_schema_history");
    }

    @Test
    void healthEndpointIsUpWithoutRabbitMq() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exposesOnlyHealthActuatorEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isNotFound());
    }
}
