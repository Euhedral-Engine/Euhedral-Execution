package io.euhedral_execution.spring.core.protocols.grpc.protos;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class GrpcTransportServiceGrpc {

  private GrpcTransportServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
      io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getUnaryMethodMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UnaryMethod",
      requestType = io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.class,
      responseType = io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
      io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getUnaryMethodMethod() {
    io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getUnaryMethodMethod;
    if ((getUnaryMethodMethod = GrpcTransportServiceGrpc.getUnaryMethodMethod) == null) {
      synchronized (GrpcTransportServiceGrpc.class) {
        if ((getUnaryMethodMethod = GrpcTransportServiceGrpc.getUnaryMethodMethod) == null) {
          GrpcTransportServiceGrpc.getUnaryMethodMethod = getUnaryMethodMethod =
              io.grpc.MethodDescriptor.<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UnaryMethod"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcTransportServiceMethodDescriptorSupplier("UnaryMethod"))
              .build();
        }
      }
    }
    return getUnaryMethodMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
      io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getClientStreamMethodMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ClientStreamMethod",
      requestType = io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.class,
      responseType = io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
      io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getClientStreamMethodMethod() {
    io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getClientStreamMethodMethod;
    if ((getClientStreamMethodMethod = GrpcTransportServiceGrpc.getClientStreamMethodMethod) == null) {
      synchronized (GrpcTransportServiceGrpc.class) {
        if ((getClientStreamMethodMethod = GrpcTransportServiceGrpc.getClientStreamMethodMethod) == null) {
          GrpcTransportServiceGrpc.getClientStreamMethodMethod = getClientStreamMethodMethod =
              io.grpc.MethodDescriptor.<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ClientStreamMethod"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcTransportServiceMethodDescriptorSupplier("ClientStreamMethod"))
              .build();
        }
      }
    }
    return getClientStreamMethodMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
      io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getServerStreamMethodMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ServerStreamMethod",
      requestType = io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.class,
      responseType = io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
      io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getServerStreamMethodMethod() {
    io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getServerStreamMethodMethod;
    if ((getServerStreamMethodMethod = GrpcTransportServiceGrpc.getServerStreamMethodMethod) == null) {
      synchronized (GrpcTransportServiceGrpc.class) {
        if ((getServerStreamMethodMethod = GrpcTransportServiceGrpc.getServerStreamMethodMethod) == null) {
          GrpcTransportServiceGrpc.getServerStreamMethodMethod = getServerStreamMethodMethod =
              io.grpc.MethodDescriptor.<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ServerStreamMethod"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcTransportServiceMethodDescriptorSupplier("ServerStreamMethod"))
              .build();
        }
      }
    }
    return getServerStreamMethodMethod;
  }

  private static volatile io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
      io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getBidirectionalMethodMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "BidirectionalMethod",
      requestType = io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.class,
      responseType = io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
      io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getBidirectionalMethodMethod() {
    io.grpc.MethodDescriptor<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> getBidirectionalMethodMethod;
    if ((getBidirectionalMethodMethod = GrpcTransportServiceGrpc.getBidirectionalMethodMethod) == null) {
      synchronized (GrpcTransportServiceGrpc.class) {
        if ((getBidirectionalMethodMethod = GrpcTransportServiceGrpc.getBidirectionalMethodMethod) == null) {
          GrpcTransportServiceGrpc.getBidirectionalMethodMethod = getBidirectionalMethodMethod =
              io.grpc.MethodDescriptor.<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "BidirectionalMethod"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage.getDefaultInstance()))
              .setSchemaDescriptor(new GrpcTransportServiceMethodDescriptorSupplier("BidirectionalMethod"))
              .build();
        }
      }
    }
    return getBidirectionalMethodMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static GrpcTransportServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GrpcTransportServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GrpcTransportServiceStub>() {
        @java.lang.Override
        public GrpcTransportServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GrpcTransportServiceStub(channel, callOptions);
        }
      };
    return GrpcTransportServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static GrpcTransportServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GrpcTransportServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GrpcTransportServiceBlockingV2Stub>() {
        @java.lang.Override
        public GrpcTransportServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GrpcTransportServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return GrpcTransportServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static GrpcTransportServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GrpcTransportServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GrpcTransportServiceBlockingStub>() {
        @java.lang.Override
        public GrpcTransportServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GrpcTransportServiceBlockingStub(channel, callOptions);
        }
      };
    return GrpcTransportServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static GrpcTransportServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GrpcTransportServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GrpcTransportServiceFutureStub>() {
        @java.lang.Override
        public GrpcTransportServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GrpcTransportServiceFutureStub(channel, callOptions);
        }
      };
    return GrpcTransportServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * Single Request/Response
     * </pre>
     */
    default void unaryMethod(io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage request,
        io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnaryMethodMethod(), responseObserver);
    }

    /**
     * <pre>
     * Client streaming
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> clientStreamMethod(
        io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getClientStreamMethodMethod(), responseObserver);
    }

    /**
     * <pre>
     * Server streaming
     * </pre>
     */
    default void serverStreamMethod(io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage request,
        io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getServerStreamMethodMethod(), responseObserver);
    }

    /**
     * <pre>
     * Bidirectional streaming
     * </pre>
     */
    default io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> bidirectionalMethod(
        io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getBidirectionalMethodMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service GrpcTransportService.
   */
  public static abstract class GrpcTransportServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return GrpcTransportServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service GrpcTransportService.
   */
  public static final class GrpcTransportServiceStub
      extends io.grpc.stub.AbstractAsyncStub<GrpcTransportServiceStub> {
    private GrpcTransportServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GrpcTransportServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GrpcTransportServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Single Request/Response
     * </pre>
     */
    public void unaryMethod(io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage request,
        io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUnaryMethodMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Client streaming
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> clientStreamMethod(
        io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getClientStreamMethodMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Server streaming
     * </pre>
     */
    public void serverStreamMethod(io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage request,
        io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getServerStreamMethodMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Bidirectional streaming
     * </pre>
     */
    public io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> bidirectionalMethod(
        io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getBidirectionalMethodMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service GrpcTransportService.
   */
  public static final class GrpcTransportServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<GrpcTransportServiceBlockingV2Stub> {
    private GrpcTransportServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GrpcTransportServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GrpcTransportServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * Single Request/Response
     * </pre>
     */
    public io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage unaryMethod(io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getUnaryMethodMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Client streaming
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>
        clientStreamMethod() {
      return io.grpc.stub.ClientCalls.blockingClientStreamingCall(
          getChannel(), getClientStreamMethodMethod(), getCallOptions());
    }

    /**
     * <pre>
     * Server streaming
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>
        serverStreamMethod(io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getServerStreamMethodMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Bidirectional streaming
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage, io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>
        bidirectionalMethod() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getBidirectionalMethodMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service GrpcTransportService.
   */
  public static final class GrpcTransportServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<GrpcTransportServiceBlockingStub> {
    private GrpcTransportServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GrpcTransportServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GrpcTransportServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Single Request/Response
     * </pre>
     */
    public io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage unaryMethod(io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnaryMethodMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Server streaming
     * </pre>
     */
    public java.util.Iterator<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> serverStreamMethod(
        io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getServerStreamMethodMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service GrpcTransportService.
   */
  public static final class GrpcTransportServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<GrpcTransportServiceFutureStub> {
    private GrpcTransportServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected GrpcTransportServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new GrpcTransportServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Single Request/Response
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage> unaryMethod(
        io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUnaryMethodMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_UNARY_METHOD = 0;
  private static final int METHODID_SERVER_STREAM_METHOD = 1;
  private static final int METHODID_CLIENT_STREAM_METHOD = 2;
  private static final int METHODID_BIDIRECTIONAL_METHOD = 3;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_UNARY_METHOD:
          serviceImpl.unaryMethod((io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage) request,
              (io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>) responseObserver);
          break;
        case METHODID_SERVER_STREAM_METHOD:
          serviceImpl.serverStreamMethod((io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage) request,
              (io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_CLIENT_STREAM_METHOD:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.clientStreamMethod(
              (io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>) responseObserver);
        case METHODID_BIDIRECTIONAL_METHOD:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.bidirectionalMethod(
              (io.grpc.stub.StreamObserver<io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getUnaryMethodMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
              io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>(
                service, METHODID_UNARY_METHOD)))
        .addMethod(
          getClientStreamMethodMethod(),
          io.grpc.stub.ServerCalls.asyncClientStreamingCall(
            new MethodHandlers<
              io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
              io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>(
                service, METHODID_CLIENT_STREAM_METHOD)))
        .addMethod(
          getServerStreamMethodMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
              io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>(
                service, METHODID_SERVER_STREAM_METHOD)))
        .addMethod(
          getBidirectionalMethodMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage,
              io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.GrpcMessage>(
                service, METHODID_BIDIRECTIONAL_METHOD)))
        .build();
  }

  private static abstract class GrpcTransportServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    GrpcTransportServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return io.euhedral_execution.spring.core.protocols.grpc.protos.GrpcTransportServiceMd.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("GrpcTransportService");
    }
  }

  private static final class GrpcTransportServiceFileDescriptorSupplier
      extends GrpcTransportServiceBaseDescriptorSupplier {
    GrpcTransportServiceFileDescriptorSupplier() {}
  }

  private static final class GrpcTransportServiceMethodDescriptorSupplier
      extends GrpcTransportServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    GrpcTransportServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (GrpcTransportServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new GrpcTransportServiceFileDescriptorSupplier())
              .addMethod(getUnaryMethodMethod())
              .addMethod(getClientStreamMethodMethod())
              .addMethod(getServerStreamMethodMethod())
              .addMethod(getBidirectionalMethodMethod())
              .build();
        }
      }
    }
    return result;
  }
}
