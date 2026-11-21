package com.malefashion.order.integration;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.malefashion.order.support.OrderFixtures;
import com.malefashion.order.support.UpstreamFixtures;
import com.malefashion.order.support.WebIntegrationTestBase;
import com.malefashion.order.support.WireMockUpstream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.6: 4xx pass-through; GET retry tối đa 2 lần (tổng 3) cho 5xx/IO/timeout; POST batch không
 * retry.
 */
class CheckoutUpstreamFailuresIT extends WebIntegrationTestBase {

    private static final String CART_PATH =
            UpstreamFixtures.cartPath(OrderFixtures.USER_ID);
    private static final String CATALOG_PATH = WireMockUpstream.CATALOG_BATCH_PATH;

    @Test
    void passesThroughCart4xxWithoutRetry() {
        stubCart(aResponse().withStatus(404));
        ResponseEntity<String> notFound = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(notFound)).isEqualTo("CART_NOT_FOUND");
        WireMockUpstream.SERVER.verify(1, getRequestedFor(urlEqualTo(CART_PATH)));

        WireMockUpstream.reset();
        stubCart(aResponse().withStatus(409));
        ResponseEntity<String> empty = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(empty)).isEqualTo("CART_EMPTY");
        WireMockUpstream.SERVER.verify(1, getRequestedFor(urlEqualTo(CART_PATH)));

        WireMockUpstream.reset();
        stubCart(aResponse().withStatus(422));
        ResponseEntity<String> coupon = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());
        assertThat(coupon.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(errorCode(coupon)).isEqualTo("COUPON_INVALID");
        WireMockUpstream.SERVER.verify(1, getRequestedFor(urlEqualTo(CART_PATH)));

        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void returns503AfterThreeAttemptsWhenCartKeepsFailing() {
        stubCart(aResponse().withStatus(500));

        ResponseEntity<String> response = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorCode(response)).isEqualTo("SERVICE_UNAVAILABLE");
        WireMockUpstream.SERVER.verify(3, getRequestedFor(urlEqualTo(CART_PATH)));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void retriesCartGetAndSucceedsOnThirdAttempt() {
        WireMockUpstream.SERVER.stubFor(WireMock.get(urlEqualTo(CART_PATH))
                .inScenario("flaky-cart")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("second"));
        WireMockUpstream.SERVER.stubFor(WireMock.get(urlEqualTo(CART_PATH))
                .inScenario("flaky-cart")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("third"));
        WireMockUpstream.SERVER.stubFor(WireMock.get(urlEqualTo(CART_PATH))
                .inScenario("flaky-cart")
                .whenScenarioStateIs("third")
                .willReturn(okJson(UpstreamFixtures.cart(OrderFixtures.USER_ID))));
        WireMockUpstream.stubCatalog();

        ResponseEntity<String> response = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WireMockUpstream.SERVER.verify(3, getRequestedFor(urlEqualTo(CART_PATH)));
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void returns503AfterIoFailureOnCart() {
        stubCart(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        ResponseEntity<String> response = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorCode(response)).isEqualTo("SERVICE_UNAVAILABLE");
        // Transport của JDK tự gửi lại GET một lần khi connection bị reset trước khi có response,
        // nên journal của WireMock có thể ghi 2 request cho mỗi attempt của ứng dụng. Ở đây chỉ
        // khẳng định đã retry; số attempt đúng 3 được kiểm tra chính xác ở test 5xx và timeout.
        assertThat(WireMockUpstream.SERVER.findAll(getRequestedFor(urlEqualTo(CART_PATH))))
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void returns503AfterReadTimeoutOnCart() {
        stubCart(aResponse().withFixedDelay(6500)
                .withHeader("Content-Type", "application/json")
                .withBody(UpstreamFixtures.cart(OrderFixtures.USER_ID)));

        ResponseEntity<String> response = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorCode(response)).isEqualTo("SERVICE_UNAVAILABLE");
        WireMockUpstream.SERVER.verify(3, getRequestedFor(urlEqualTo(CART_PATH)));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void catalog5xxIsNotRetriedAndBecomes503() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.SERVER.stubFor(post(urlEqualTo(CATALOG_PATH))
                .willReturn(aResponse().withStatus(500)));

        ResponseEntity<String> response = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorCode(response)).isEqualTo("SERVICE_UNAVAILABLE");
        WireMockUpstream.SERVER.verify(1, postRequestedFor(urlEqualTo(CATALOG_PATH)));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void catalog4xxIsPassedThroughWithoutRetry() {
        WireMockUpstream.stubCart(OrderFixtures.USER_ID);
        WireMockUpstream.SERVER.stubFor(post(urlEqualTo(CATALOG_PATH))
                .willReturn(WireMockUpstream.problem(400, "VALIDATION_ERROR")));

        ResponseEntity<String> response = checkout(UpstreamFixtures.checkoutBody(),
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
        WireMockUpstream.SERVER.verify(1, postRequestedFor(urlEqualTo(CATALOG_PATH)));
        assertThat(orderRepository.count()).isZero();
    }

    private void stubCart(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
                                  response) {
        WireMockUpstream.SERVER.stubFor(WireMock.get(urlEqualTo(CART_PATH)).willReturn(response));
    }
}
