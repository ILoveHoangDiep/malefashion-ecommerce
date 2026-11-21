package com.malefashion.order.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cấu hình sai phải làm context không khởi động được, không chỉ log cảnh báo.
 */
class OrderPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void bindsValidConfiguration() {
        runner.withPropertyValues(propertiesWith(null, null))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OrderProperties properties = context.getBean(OrderProperties.class);
                    assertThat(properties.saga().timeout()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.saga().refundMaxRetries()).isEqualTo(2);
                    assertThat(properties.saga().commitTimeout()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(properties.saga().commitMaxRetries()).isEqualTo(2);
                    assertThat(properties.saga().commitRetryBackoff())
                            .isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.saga().commitRetryBackoffMultiplier()).isEqualTo(2.0);
                    assertThat(properties.shippingFee()).isEqualByComparingTo("30000.00");
                    assertThat(properties.messaging().retry().maxAttempts()).isEqualTo(3);
                    assertThat(properties.outbox().batchSize()).isEqualTo(100);
                    assertThat(properties.outbox().confirmTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.messaging().consumerName()).isEqualTo("order-service");
                    assertThat(properties.messaging().listener().concurrency()).isEqualTo(1);
                    assertThat(properties.messaging().listener().queue())
                            .isEqualTo("order-service.saga.events");
                });
    }

    @ParameterizedTest(name = "{0}={1} prevents startup")
    @MethodSource("invalidOverrides")
    void rejectsInvalidConfiguration(String key, String value) {
        runner.withPropertyValues(propertiesWith(key, value))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(rootMessages(context.getStartupFailure())).contains("order");
                });
    }

    private static Stream<Arguments> invalidOverrides() {
        return Stream.of(
                Arguments.of("order.saga.refund-max-retries", "-1"),
                Arguments.of("order.saga.refund-retry-backoff", "PT0S"),
                Arguments.of("order.saga.refund-retry-backoff-multiplier", "0.5"),
                Arguments.of("order.saga.timeout", "PT0S"),
                Arguments.of("order.saga.compensation-timeout", "PT0S"),
                Arguments.of("order.saga.commit-timeout", "PT0S"),
                Arguments.of("order.saga.commit-max-retries", "-1"),
                Arguments.of("order.saga.commit-retry-backoff", "PT0S"),
                Arguments.of("order.saga.commit-retry-backoff-multiplier", "0.5"),
                Arguments.of("order.internal-api-key", " "),
                Arguments.of("order.shipping-fee", "-0.01"),
                Arguments.of("order.timeout-scan-interval-ms", "0"),
                Arguments.of("order.outbox.publish-interval-ms", "0"),
                Arguments.of("order.outbox.batch-size", "0"),
                Arguments.of("order.outbox.confirm-timeout", "PT0S"),
                Arguments.of("order.messaging.consumer-name", " "),
                Arguments.of("order.messaging.listener.concurrency", "0"),
                Arguments.of("order.messaging.listener.queue", " "),
                Arguments.of("order.messaging.retry.max-attempts", "0"));
    }

    private static String[] propertiesWith(String overrideKey, String overrideValue) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("order.saga.timeout", "PT10M");
        properties.put("order.saga.compensation-timeout", "PT2M");
        properties.put("order.saga.refund-max-retries", "2");
        properties.put("order.saga.refund-retry-backoff", "PT1M");
        properties.put("order.saga.refund-retry-backoff-multiplier", "2.0");
        properties.put("order.saga.commit-timeout", "PT1M");
        properties.put("order.saga.commit-max-retries", "2");
        properties.put("order.saga.commit-retry-backoff", "PT30S");
        properties.put("order.saga.commit-retry-backoff-multiplier", "2.0");
        properties.put("order.shipping-fee", "30000.00");
        properties.put("order.timeout-scan-interval-ms", "5000");
        properties.put("order.internal-api-key", "test-internal-api-key");
        properties.put("order.cart-base-url", "http://cart-coupon-service:8083");
        properties.put("order.catalog-base-url", "http://catalog-service:8082");
        properties.put("order.mock-scenario-enabled", "false");
        properties.put("order.outbox.publish-interval-ms", "500");
        properties.put("order.outbox.batch-size", "100");
        properties.put("order.outbox.confirm-timeout", "PT5S");
        properties.put("order.outbox.publisher.enabled", "false");
        properties.put("order.messaging.consumer-name", "order-service");
        properties.put("order.messaging.listener.auto-startup", "false");
        properties.put("order.messaging.listener.concurrency", "1");
        properties.put("order.messaging.listener.queue", "order-service.saga.events");
        properties.put("order.messaging.retry.max-attempts", "3");
        properties.put("order.messaging.retry.initial-interval-ms", "500");
        properties.put("order.messaging.retry.multiplier", "2.0");
        properties.put("order.messaging.retry.max-interval-ms", "5000");
        if (overrideKey != null) {
            properties.put(overrideKey, overrideValue);
        }
        return properties.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private static String rootMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return messages.toString();
    }

    @Configuration
    @EnableConfigurationProperties(OrderProperties.class)
    static class PropertiesConfig {
    }
}
