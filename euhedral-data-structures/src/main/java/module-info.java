module euhedral.data_structures {
    requires static lombok;
    requires java.management;
    requires jdk.management;
    requires org.jspecify;

    exports io.euhedral_execution.data_structures.atomics;
    exports io.euhedral_execution.data_structures.queues;
    exports io.euhedral_execution.data_structures.queues.common;
}
