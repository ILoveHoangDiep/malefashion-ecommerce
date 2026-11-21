package com.malefashion.order.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Một WireMockServer dùng chung cho toàn bộ IT (static của JVM test). Mỗi lớp tự reset stub và
 * request journal trong {@code @BeforeEach}.
 */
public final class WireMockUpstream {

    public static final String CATALOG_BATCH_PATH = "/internal/v1/variants/batch";

    public static final WireMockServer SERVER =
            new WireMockServer(options().dynamicPort());

    static {
        SERVER.start();
    }

    private WireMockUpstream() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("order.cart-base-url", SERVER::baseUrl);
        registry.add("order.catalog-base-url", SERVER::baseUrl);
    }

    public static void reset() {
        SERVER.resetAll();
    }

    public static void stubCart(UUID userId) {
        stubCart(userId, UpstreamFixtures.cart(userId));
    }

    public static void stubCart(UUID userId, String json) {
        SERVER.stubFor(get(urlEqualTo(UpstreamFixtures.cartPath(userId)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));
    }

    public static void stubCatalog() {
        stubCatalog(UpstreamFixtures.catalog());
    }

    public static void stubCatalog(String json) {
        SERVER.stubFor(post(urlEqualTo(CATALOG_BATCH_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));
    }

    public static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder problem(
            int status, String code) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("""
                        {
                          "type": "https://malefashion.local/errors/%s",
                          "title": "Upstream error",
                          "status": %d,
                          "detail": "upstream failed",
                          "code": "%s"
                        }
                        """.formatted(code.toLowerCase(java.util.Locale.ROOT)
                        .replace('_', '-'), status, code));
    }
}
