package com.malefashion.order.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Validate response REST với {@code contracts/openapi/order-api.yaml} sống trên đĩa; contract đổi
 * là test đỏ ngay. Chỉ validate response, không cần dựng request đầy đủ.
 */
public final class OrderApiContract {

    /**
     * order-api.yaml không khai báo {@code additionalProperties: false} cho OrderResponse (chỉ
     * CheckoutRequest/ShippingAddress bị đóng), nên theo OpenAPI 3.0 object được phép có field
     * thêm — đó là chỗ đặt refundState/sagaStep/commitUnconfirmed mà đặc tả yêu cầu. Validator mặc
     * định tự thêm additionalProperties=false cho mọi schema có properties, nên tắt riêng quy tắc
     * đó; các ràng buộc còn lại (required, type, format, enum, pattern) vẫn được kiểm tra đầy đủ.
     */
    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(locateSpec().toUri().toString())
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.schema.additionalProperties",
                            ValidationReport.Level.IGNORE)
                    .build())
            .build();

    private OrderApiContract() {
    }

    public static void assertResponseValid(String path, String method,
                                           ResponseEntity<String> response) {
        SimpleResponse.Builder builder = SimpleResponse.Builder
                .status(response.getStatusCode().value());
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null) {
            builder.withContentType(contentType.toString());
        }
        if (response.getBody() != null) {
            builder.withBody(response.getBody());
        }
        ValidationReport report = VALIDATOR.validateResponse(path,
                Request.Method.valueOf(method.toUpperCase(Locale.ROOT)), builder.build());
        if (report.hasErrors()) {
            String details = report.getMessages().stream()
                    .map(message -> message.getKey() + ": " + message.getMessage())
                    .collect(Collectors.joining("\n  "));
            throw new AssertionError("Response does not match order-api.yaml for " + method + " "
                    + path + " [" + response.getStatusCode().value() + "]:\n  " + details
                    + "\nBody:\n" + response.getBody());
        }
    }

    private static Path locateSpec() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts").resolve("openapi")
                    .resolve("order-api.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("contracts/openapi/order-api.yaml not found above "
                + Paths.get("").toAbsolutePath());
    }
}
