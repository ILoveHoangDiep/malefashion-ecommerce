package com.malefashion.order.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Bắt toàn bộ log của JVM trong một khoảng test để chứng minh không có email/key/Authorization.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(Logger root, ListAppender<ILoggingEvent> appender) {
        this.root = root;
        this.appender = appender;
    }

    public static LogCapture attach() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        return new LogCapture(root, appender);
    }

    public List<String> lines() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
