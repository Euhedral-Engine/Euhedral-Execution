open module euhedral.spring.core.test {
    requires euhedral.core;
    requires euhedral.hashing;

    requires euhedral.spring.core;

    requires io.grpc;
    requires org.junit.jupiter.api;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.boot.test;
    requires spring.context;
    requires spring.grpc.core;
    requires spring.messaging;
    requires reactor.core;
    requires spring.beans;

    exports test_impl;
}