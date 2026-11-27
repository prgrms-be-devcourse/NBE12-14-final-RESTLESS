package com.restless.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/** Records one class's actual Logback events and detaches at the end of the scenario. */
public final class RecordedLogs implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public RecordedLogs(Class<?> source) {
        logger = (Logger) LoggerFactory.getLogger(source);
        appender.start();
        logger.addAppender(appender);
    }

    public List<String> entries() {
        return appender.list.stream()
                .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                .toList();
    }

    /** Inspect arguments and the throwable chain as well as the rendered message. */
    public List<String> payloads() {
        return appender.list.stream().map(event -> {
            String throwable = event.getThrowableProxy() == null
                    ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy());
            return event.getLevel() + " " + event.getFormattedMessage()
                    + " arguments=" + Arrays.deepToString(event.getArgumentArray()) + "\n" + throwable;
        }).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
