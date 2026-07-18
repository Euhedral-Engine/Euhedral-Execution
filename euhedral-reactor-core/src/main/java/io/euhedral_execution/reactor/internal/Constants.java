package io.euhedral_execution.reactor.internal;

public class Constants {
    public static final String LOGGER_PREFIX = "euhedral.reactor.core.";

    public static String getLoggerName(Class<?> clazz) {
        return LOGGER_PREFIX + clazz.getSimpleName();
    }

    private Constants() {

    }
}
