module euhedral.reactor.core {
    requires static lombok;

    requires euhedral.core;
    requires euhedral.data_structures;
    requires euhedral.hashing;

    requires micrometer.core;
    requires org.jspecify;
    requires org.reactivestreams;
    requires reactor.core;
    requires org.slf4j;

    exports io.euhedral_execution.reactor;
    exports io.euhedral_execution.reactor.common;
}