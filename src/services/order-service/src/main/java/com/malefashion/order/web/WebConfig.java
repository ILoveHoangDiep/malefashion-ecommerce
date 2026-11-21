package com.malefashion.order.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Bảo vệ toàn bộ API đơn hàng bằng interceptor; actuator và các path khác không bị ảnh hưởng.
 * Mapper chung vẫn bỏ qua field lạ (cần cho dữ liệu upstream), nhưng request body vào controller
 * phải từ chối field lạ theo {@code additionalProperties:false} của contract.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RequestContextInterceptor requestContextInterceptor;
    private final ObjectMapper objectMapper;

    public WebConfig(RequestContextInterceptor requestContextInterceptor, ObjectMapper objectMapper) {
        this.requestContextInterceptor = requestContextInterceptor;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestContextInterceptor)
                .addPathPatterns("/api/v1/orders", "/api/v1/orders/**");
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        ObjectMapper strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        converters.replaceAll(converter ->
                converter instanceof MappingJackson2HttpMessageConverter
                        ? new MappingJackson2HttpMessageConverter(strictMapper)
                        : converter);
    }
}
