module euhedral.hardware_utils {
    requires static lombok;
    requires static org.jspecify;
    requires it.unimi.dsi.fastutil;
    requires org.slf4j;
    requires java.management;
    requires jdk.management;

    exports io.euhedral_execution.hardware_utils.affinity;
    exports io.euhedral_execution.hardware_utils.linux;
    exports io.euhedral_execution.hardware_utils.macos;
    exports io.euhedral_execution.hardware_utils.monitor;
    exports io.euhedral_execution.hardware_utils.monitor.sampling;
    exports io.euhedral_execution.hardware_utils.monitor.sampling.enums;
    exports io.euhedral_execution.hardware_utils.monitor.sampling.primitives;
    exports io.euhedral_execution.hardware_utils.monitor.sampling.samples;
    exports io.euhedral_execution.hardware_utils.monitor.sampling.signals;
    exports io.euhedral_execution.hardware_utils.topology;
    exports io.euhedral_execution.hardware_utils.util;
    exports io.euhedral_execution.hardware_utils.windows;
}
