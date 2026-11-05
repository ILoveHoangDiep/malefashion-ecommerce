package com.malefashion.payment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Sinh mã giao dịch giả lập {@code MOCK-TXN-yyyyMMdd-NNNNNN}.
 *
 * <p>Phần số lấy từ sequence PostgreSQL nên duy nhất trên toàn cụm, kể cả khi chạy nhiều instance.
 * Ngày lấy theo UTC cho khớp với timestamp trong contract.</p>
 */
@Component
public class ProviderTransactionIdGenerator {

    static final String SEQUENCE_NAME = "payment_provider_transaction_id_seq";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbcTemplate;

    public ProviderTransactionIdGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String next() {
        Long sequence = jdbcTemplate.queryForObject(
                "SELECT nextval('" + SEQUENCE_NAME + "')", Long.class);
        Objects.requireNonNull(sequence, "sequence returned null");
        return format(LocalDate.now(ZoneOffset.UTC), sequence);
    }

    static String format(LocalDate date, long sequence) {
        return "MOCK-TXN-" + DATE_FORMAT.format(date) + "-" + String.format("%06d", sequence);
    }
}
