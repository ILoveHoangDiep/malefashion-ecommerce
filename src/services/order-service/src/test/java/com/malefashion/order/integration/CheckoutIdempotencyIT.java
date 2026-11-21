package com.malefashion.order.integration;

import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.UpstreamFixtures;
import com.malefashion.order.support.WebIntegrationTestBase;
import com.malefashion.order.support.WireMockUpstream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.3: replay 200 + header, cùng key khác body 409, hai request song song chỉ tạo một đơn và
 * không request nào trả 500.
 */
class CheckoutIdempotencyIT extends WebIntegrationTestBase {

    @Test
    void replaysWithoutCreatingAnotherOrderOrCallingCartAgain() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();
        UUID key = UUID.randomUUID();
        String body = UpstreamFixtures.checkoutBody();

        ResponseEntity<String> first = checkout(body, key);
        ResponseEntity<String> second = checkout(body, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json(second).path("orderId").asText())
                .isEqualTo(json(first).path("orderId").asText());
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
        WireMockUpstream.SERVER.verify(1, getRequestedFor(
                urlEqualTo(UpstreamFixtures.cartPath(OrderFixtures.USER_ID))));
    }

    @Test
    void sameKeyWithDifferentBodyReturnsConflict() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();
        UUID key = UUID.randomUUID();

        assertThat(checkout(UpstreamFixtures.checkoutBody(), key).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> reused = checkout(
                UpstreamFixtures.checkoutBody().replace("SUMMER10", "WINTER20"), key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }

    @Test
    void parallelRequestsWithSameKeyCreateExactlyOneOrderWithoutServerErrors() throws Exception {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.stubCatalog();
        UUID key = UUID.randomUUID();
        String body = UpstreamFixtures.checkoutBody();

        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<ResponseEntity<String>>> calls = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                calls.add(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return checkout(body, key);
                });
            }
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> call : calls) {
                futures.add(pool.submit(call));
            }
            start.countDown();

            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses).extracting(response -> response.getStatusCode().value())
                    .noneMatch(status -> status >= 500);
            assertThat(responses.stream()
                    .filter(response -> response.getStatusCode() == HttpStatus.CREATED)
                    .count()).isEqualTo(1);
            assertThat(responses.stream()
                    .filter(response -> response.getStatusCode() == HttpStatus.OK)
                    .count()).isEqualTo(callers - 1);
            assertThat(responses.stream()
                    .map(response -> json(response).path("orderId").asText())
                    .distinct())
                    .hasSize(1);
            assertThat(orderRepository.count()).isEqualTo(1);
            assertThat(idempotencyRepository.count()).isEqualTo(1);
            assertThat(outboxEventRepository.count()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }
}
