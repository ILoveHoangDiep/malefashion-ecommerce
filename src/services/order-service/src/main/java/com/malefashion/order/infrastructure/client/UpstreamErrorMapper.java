package com.malefashion.order.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.malefashion.order.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Giữ nguyên status + code của ProblemDetail khi cart/catalog trả 4xx. Nếu body không đọc được thì
 * dùng bảng mã dự phòng theo contract; không tự đổi 4xx thành 503.
 */
@Component
public class UpstreamErrorMapper {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private final ObjectMapper objectMapper;

    public UpstreamErrorMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ApiException passthrough(RestClientResponseException failure,
                                    String service,
                                    Map<Integer, String> fallbackCodes) {
        int status = failure.getStatusCode().value();
        UpstreamProblem problem = parse(failure.getResponseBodyAsString());
        String code = problem != null && problem.code() != null
                && CODE_PATTERN.matcher(problem.code()).matches()
                ? problem.code()
                : fallbackCodes.getOrDefault(status, "UPSTREAM_ERROR");
        String title = problem != null && problem.title() != null
                ? problem.title() : "Upstream error";
        String detail = problem != null && problem.detail() != null
                ? problem.detail()
                : "Dịch vụ " + service + " trả lỗi " + status + ".";
        HttpStatus httpStatus = HttpStatus.resolve(status);
        if (httpStatus == null) {
            httpStatus = HttpStatus.BAD_GATEWAY;
        }
        return new ApiException(httpStatus, code, title, detail, List.of());
    }

    private UpstreamProblem parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, UpstreamProblem.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
