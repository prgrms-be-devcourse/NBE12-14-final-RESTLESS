package com.restless.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

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

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
