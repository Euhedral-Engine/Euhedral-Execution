module euhedral.core {
    requires static lombok;

    requires euhedral.data.structures;
    requires euhedral.hardware_utils;
    requires euhedral.hashing;

    requires micrometer.core;
    requires org.jctools.core;
    requires org.jspecify;
    requires org.slf4j;

    exports io.euhedral_execution.core.config;
    exports io.euhedral_execution.core.control_plane;
    exports io.euhedral_execution.core.flow_control;
    exports io.euhedral_execution.core.frames;
    exports io.euhedral_execution.core.generics;
    exports io.euhedral_execution.core.impl;
    exports io.euhedral_execution.core.ingest;
    exports io.euhedral_execution.core.metrics;
    exports io.euhedral_execution.core.utils;
}