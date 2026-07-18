package io.euhedral_execution.spring.core.internal;

public class Constants {
    public static final String LOGGER_PREFIX = "euhedral.spring.core.";

    public static String getLoggerName(Class<?> clazz) {
        return LOGGER_PREFIX + clazz.getSimpleName();
    }

    private Constants() {

    }
}
