package com.malefashion.order.integration;

import com.malefashion.order.support.HttpTestSupport;
import com.malefashion.order.support.LogCapture;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.UpstreamFixtures;
import com.malefashion.order.support.WebIntegrationTestBase;
import com.malefashion.order.support.WireMockUpstream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.10: log ứng dụng không chứa email, internal key hay Authorization, kể cả ở request lỗi.
 */
class CheckoutLoggingIT extends WebIntegrationTestBase {

    @Test
    void logsNeverContainEmailInternalKeyOrAuthorization() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();

        try (LogCapture logs = LogCapture.attach()) {
            assertThat(checkout(UpstreamFixtures.checkoutBody(), UUID.randomUUID())
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(checkout("{}", UUID.randomUUID()).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(checkout(UpstreamFixtures.checkoutBody(), UUID.randomUUID(),
                    OrderFixtures.USER_ID, "STAFF").getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);

            List<String> lines = logs.lines();
            assertThat(lines).as("log phải bắt được request (userId/traceId)")
                    .anyMatch(line -> line.contains(OrderFixtures.USER_ID.toString()));
            assertThat(lines)
                    .noneMatch(line -> line.contains(OrderFixtures.USER_EMAIL));
            assertThat(lines)
                    .noneMatch(line -> line.contains(HttpTestSupport.INTERNAL_API_KEY));
            assertThat(lines)
                    .noneMatch(line -> line.toLowerCase().contains("authorization"));
        }
    }
}
