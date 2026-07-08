module euhedral.hardware_utils {
    requires static lombok;
    requires static org.jspecify;

    requires it.unimi.dsi.fastutil;
    requires org.slf4j;
    requires java.management;
    requires jdk.management;

    exports io.euhedral_execution.hardware_utils;
    exports io.euhedral_execution.hardware_utils.common;
    exports io.euhedral_execution.hardware_utils.linux;
    exports io.euhedral_execution.hardware_utils.macOS;
    exports io.euhedral_execution.hardware_utils.windows;
}