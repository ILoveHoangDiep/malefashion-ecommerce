package com.malefashion.order.domain.support;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Sinh orderNumber {@code MF-YYYYMMDD-XXXXXX}; ngày theo UTC, X là A-Z0-9.
 */
public final class OrderNumberGenerator {

    public static final Pattern ORDER_NUMBER_PATTERN = Pattern.compile("^MF-\\d{8}-[A-Z0-9]{6}$");

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int SUFFIX_LENGTH = 6;

    private final Clock clock;
    private final RandomGenerator random;

    public OrderNumberGenerator(Clock clock) {
        this(clock, new SecureRandom());
    }

    public OrderNumberGenerator(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    public String next() {
        LocalDate utcDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        StringBuilder value = new StringBuilder("MF-")
                .append(DATE_FORMATTER.format(utcDate))
                .append('-');
        for (int index = 0; index < SUFFIX_LENGTH; index++) {
            value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }
}
