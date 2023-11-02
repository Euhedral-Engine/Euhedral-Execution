module euhedral.hardware_utils {
    requires static lombok;
    requires static org.jspecify;

    requires it.unimi.dsi.fastutil;
    requires org.slf4j;
    requires java.management;
    requires jdk.management;

    exports euhedral.hardware_utils;
    exports euhedral.hardware_utils.common;
    exports euhedral.hardware_utils.linux;
    exports euhedral.hardware_utils.macOS;
    exports euhedral.hardware_utils.windows;
}