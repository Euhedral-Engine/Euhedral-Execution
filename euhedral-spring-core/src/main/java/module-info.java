module euhedral.spring.core {
    requires static lombok;

    requires euhedral.core;
    requires euhedral.hashing;

    requires euhedral.data.structures;
    requires euhedral.reactor.core;

    requires com.google.common;
    requires com.google.protobuf;
    requires io.grpc;
    requires io.grpc.protobuf;
    requires io.grpc.stub;
    requires it.unimi.dsi.fastutil;
    requires jakarta.annotation;
    requires kafka.clients;
    requires micrometer.core;
    requires org.apache.commons.lang3;
    requires org.jctools.core;
    requires org.jspecify;
    requires org.reactivestreams;
    requires org.slf4j;
    requires reactor.core;
    requires spring.beans;
    requires spring.boot.autoconfigure;
    requires spring.boot.kafka;
    requires spring.cloud.context;
    requires spring.cloud.stream;
    requires spring.cloud.stream.binder.kafka;
    requires spring.cloud.stream.binder.kafka.core;
    requires spring.context;
    requires spring.grpc.core;
    requires spring.integration.core;
    requires spring.messaging;

    exports io.euhedral_execution.spring.core.frames;
    exports io.euhedral_execution.spring.core.utils;
    exports io.euhedral_execution.spring.core.transport.grpc.protos;
    exports io.euhedral_execution.spring.core.transport.grpc;
    exports io.euhedral_execution.spring.core.transport.kafka;
}