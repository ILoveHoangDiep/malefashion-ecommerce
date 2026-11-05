package com.malefashion.payment.application;

import com.malefashion.payment.config.PaymentProperties;
import com.malefashion.payment.domain.model.PaymentScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Quyết định mock scenario thực sự được dùng cho một payment.
 *
 * <p>Chỉ khi {@code payment.mock.honor-scenario=true} mới đọc {@code mockScenario} từ command.
 * Mọi trường hợp còn lại (tắt cờ, thiếu field, giá trị lạ) đều coi như {@code SUCCESS} để không làm
 * hỏng luồng của order-service.</p>
 */
@Component
public class MockScenarioResolver {

    private static final Logger log = LoggerFactory.getLogger(MockScenarioResolver.class);

    private final PaymentProperties properties;

    public MockScenarioResolver(PaymentProperties properties) {
        this.properties = properties;
    }

    public PaymentScenario resolve(String requestedScenario) {
        if (!properties.mock().honorScenario()) {
            return PaymentScenario.SUCCESS;
        }
        if (requestedScenario == null || requestedScenario.isBlank()) {
            return PaymentScenario.SUCCESS;
        }
        try {
            return PaymentScenario.valueOf(requestedScenario.trim());
        } catch (IllegalArgumentException unknownScenario) {
            log.warn("Unknown mockScenario '{}', falling back to SUCCESS", requestedScenario);
            return PaymentScenario.SUCCESS;
        }
    }

    public boolean isSilentOnTimeout() {
        return properties.mock().silentOnTimeout();
    }
}
