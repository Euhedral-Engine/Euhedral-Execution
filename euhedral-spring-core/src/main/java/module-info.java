module euhedral.spring.core {
    requires static lombok;

    requires euhedral.core;
    requires euhedral.hashing;

    requires euhedral.data.structures;
    requires com.google.common;
    requires com.google.protobuf;
    requires io.grpc;
    requires io.grpc.protobuf;
    requires io.grpc.stub;
    requires org.jctools.core;
    requires org.jspecify;
    requires org.reactivestreams;
    requires reactor.core;
    requires spring.context;
    requires spring.grpc.core;

    exports io.euhedral_execution.spring.core.frames;
    exports io.euhedral_execution.spring.core.utils;
    exports io.euhedral_execution.spring.core.protocols.grpc.base;
    exports io.euhedral_execution.spring.core.protocols.grpc.protos;
}