package com.rgp.loadtest.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class PluginServiceGrpc {

  private PluginServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "PluginService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.ConnectAndCallRequest,
      com.rgp.loadtest.grpc.ZmqResponse> getConnectAndCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ConnectAndCall",
      requestType = com.rgp.loadtest.grpc.ConnectAndCallRequest.class,
      responseType = com.rgp.loadtest.grpc.ZmqResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.ConnectAndCallRequest,
      com.rgp.loadtest.grpc.ZmqResponse> getConnectAndCallMethod() {
    io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.ConnectAndCallRequest, com.rgp.loadtest.grpc.ZmqResponse> getConnectAndCallMethod;
    if ((getConnectAndCallMethod = PluginServiceGrpc.getConnectAndCallMethod) == null) {
      synchronized (PluginServiceGrpc.class) {
        if ((getConnectAndCallMethod = PluginServiceGrpc.getConnectAndCallMethod) == null) {
          PluginServiceGrpc.getConnectAndCallMethod = getConnectAndCallMethod =
              io.grpc.MethodDescriptor.<com.rgp.loadtest.grpc.ConnectAndCallRequest, com.rgp.loadtest.grpc.ZmqResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ConnectAndCall"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.ConnectAndCallRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.ZmqResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginServiceMethodDescriptorSupplier("ConnectAndCall"))
              .build();
        }
      }
    }
    return getConnectAndCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.PluginRequest,
      com.rgp.loadtest.grpc.PluginResponse> getCallMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Call",
      requestType = com.rgp.loadtest.grpc.PluginRequest.class,
      responseType = com.rgp.loadtest.grpc.PluginResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.PluginRequest,
      com.rgp.loadtest.grpc.PluginResponse> getCallMethod() {
    io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.PluginRequest, com.rgp.loadtest.grpc.PluginResponse> getCallMethod;
    if ((getCallMethod = PluginServiceGrpc.getCallMethod) == null) {
      synchronized (PluginServiceGrpc.class) {
        if ((getCallMethod = PluginServiceGrpc.getCallMethod) == null) {
          PluginServiceGrpc.getCallMethod = getCallMethod =
              io.grpc.MethodDescriptor.<com.rgp.loadtest.grpc.PluginRequest, com.rgp.loadtest.grpc.PluginResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Call"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.PluginRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.PluginResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginServiceMethodDescriptorSupplier("Call"))
              .build();
        }
      }
    }
    return getCallMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.DisconnectRequest,
      com.rgp.loadtest.grpc.ZmqResponse> getDisconnectMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Disconnect",
      requestType = com.rgp.loadtest.grpc.DisconnectRequest.class,
      responseType = com.rgp.loadtest.grpc.ZmqResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.DisconnectRequest,
      com.rgp.loadtest.grpc.ZmqResponse> getDisconnectMethod() {
    io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.DisconnectRequest, com.rgp.loadtest.grpc.ZmqResponse> getDisconnectMethod;
    if ((getDisconnectMethod = PluginServiceGrpc.getDisconnectMethod) == null) {
      synchronized (PluginServiceGrpc.class) {
        if ((getDisconnectMethod = PluginServiceGrpc.getDisconnectMethod) == null) {
          PluginServiceGrpc.getDisconnectMethod = getDisconnectMethod =
              io.grpc.MethodDescriptor.<com.rgp.loadtest.grpc.DisconnectRequest, com.rgp.loadtest.grpc.ZmqResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Disconnect"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.DisconnectRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.ZmqResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginServiceMethodDescriptorSupplier("Disconnect"))
              .build();
        }
      }
    }
    return getDisconnectMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.DisconnectBatchRequest,
      com.rgp.loadtest.grpc.ZmqResponse> getDisconnectBatchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DisconnectBatch",
      requestType = com.rgp.loadtest.grpc.DisconnectBatchRequest.class,
      responseType = com.rgp.loadtest.grpc.ZmqResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.DisconnectBatchRequest,
      com.rgp.loadtest.grpc.ZmqResponse> getDisconnectBatchMethod() {
    io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.DisconnectBatchRequest, com.rgp.loadtest.grpc.ZmqResponse> getDisconnectBatchMethod;
    if ((getDisconnectBatchMethod = PluginServiceGrpc.getDisconnectBatchMethod) == null) {
      synchronized (PluginServiceGrpc.class) {
        if ((getDisconnectBatchMethod = PluginServiceGrpc.getDisconnectBatchMethod) == null) {
          PluginServiceGrpc.getDisconnectBatchMethod = getDisconnectBatchMethod =
              io.grpc.MethodDescriptor.<com.rgp.loadtest.grpc.DisconnectBatchRequest, com.rgp.loadtest.grpc.ZmqResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DisconnectBatch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.DisconnectBatchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.ZmqResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginServiceMethodDescriptorSupplier("DisconnectBatch"))
              .build();
        }
      }
    }
    return getDisconnectBatchMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.SyncSessionsRequest,
      com.rgp.loadtest.grpc.SyncSessionsResponse> getFindSessionsToRemoveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FindSessionsToRemove",
      requestType = com.rgp.loadtest.grpc.SyncSessionsRequest.class,
      responseType = com.rgp.loadtest.grpc.SyncSessionsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.SyncSessionsRequest,
      com.rgp.loadtest.grpc.SyncSessionsResponse> getFindSessionsToRemoveMethod() {
    io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.SyncSessionsRequest, com.rgp.loadtest.grpc.SyncSessionsResponse> getFindSessionsToRemoveMethod;
    if ((getFindSessionsToRemoveMethod = PluginServiceGrpc.getFindSessionsToRemoveMethod) == null) {
      synchronized (PluginServiceGrpc.class) {
        if ((getFindSessionsToRemoveMethod = PluginServiceGrpc.getFindSessionsToRemoveMethod) == null) {
          PluginServiceGrpc.getFindSessionsToRemoveMethod = getFindSessionsToRemoveMethod =
              io.grpc.MethodDescriptor.<com.rgp.loadtest.grpc.SyncSessionsRequest, com.rgp.loadtest.grpc.SyncSessionsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FindSessionsToRemove"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.SyncSessionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.SyncSessionsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginServiceMethodDescriptorSupplier("FindSessionsToRemove"))
              .build();
        }
      }
    }
    return getFindSessionsToRemoveMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.BatchPluginRequest,
      com.rgp.loadtest.grpc.BatchPluginResponse> getCallBatchInternallyMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "CallBatchInternally",
      requestType = com.rgp.loadtest.grpc.BatchPluginRequest.class,
      responseType = com.rgp.loadtest.grpc.BatchPluginResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.BatchPluginRequest,
      com.rgp.loadtest.grpc.BatchPluginResponse> getCallBatchInternallyMethod() {
    io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.BatchPluginRequest, com.rgp.loadtest.grpc.BatchPluginResponse> getCallBatchInternallyMethod;
    if ((getCallBatchInternallyMethod = PluginServiceGrpc.getCallBatchInternallyMethod) == null) {
      synchronized (PluginServiceGrpc.class) {
        if ((getCallBatchInternallyMethod = PluginServiceGrpc.getCallBatchInternallyMethod) == null) {
          PluginServiceGrpc.getCallBatchInternallyMethod = getCallBatchInternallyMethod =
              io.grpc.MethodDescriptor.<com.rgp.loadtest.grpc.BatchPluginRequest, com.rgp.loadtest.grpc.BatchPluginResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "CallBatchInternally"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.BatchPluginRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.BatchPluginResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginServiceMethodDescriptorSupplier("CallBatchInternally"))
              .build();
        }
      }
    }
    return getCallBatchInternallyMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.SyncSessionsRequest,
      com.rgp.loadtest.grpc.ZmqResponse> getSyncSessionsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SyncSessions",
      requestType = com.rgp.loadtest.grpc.SyncSessionsRequest.class,
      responseType = com.rgp.loadtest.grpc.ZmqResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.SyncSessionsRequest,
      com.rgp.loadtest.grpc.ZmqResponse> getSyncSessionsMethod() {
    io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.SyncSessionsRequest, com.rgp.loadtest.grpc.ZmqResponse> getSyncSessionsMethod;
    if ((getSyncSessionsMethod = PluginServiceGrpc.getSyncSessionsMethod) == null) {
      synchronized (PluginServiceGrpc.class) {
        if ((getSyncSessionsMethod = PluginServiceGrpc.getSyncSessionsMethod) == null) {
          PluginServiceGrpc.getSyncSessionsMethod = getSyncSessionsMethod =
              io.grpc.MethodDescriptor.<com.rgp.loadtest.grpc.SyncSessionsRequest, com.rgp.loadtest.grpc.ZmqResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SyncSessions"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.SyncSessionsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.ZmqResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginServiceMethodDescriptorSupplier("SyncSessions"))
              .build();
        }
      }
    }
    return getSyncSessionsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.FetchConnectCommandsRequest,
      com.rgp.loadtest.grpc.FetchConnectCommandsResponse> getFetchConnectCommandsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "FetchConnectCommands",
      requestType = com.rgp.loadtest.grpc.FetchConnectCommandsRequest.class,
      responseType = com.rgp.loadtest.grpc.FetchConnectCommandsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.FetchConnectCommandsRequest,
      com.rgp.loadtest.grpc.FetchConnectCommandsResponse> getFetchConnectCommandsMethod() {
    io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.FetchConnectCommandsRequest, com.rgp.loadtest.grpc.FetchConnectCommandsResponse> getFetchConnectCommandsMethod;
    if ((getFetchConnectCommandsMethod = PluginServiceGrpc.getFetchConnectCommandsMethod) == null) {
      synchronized (PluginServiceGrpc.class) {
        if ((getFetchConnectCommandsMethod = PluginServiceGrpc.getFetchConnectCommandsMethod) == null) {
          PluginServiceGrpc.getFetchConnectCommandsMethod = getFetchConnectCommandsMethod =
              io.grpc.MethodDescriptor.<com.rgp.loadtest.grpc.FetchConnectCommandsRequest, com.rgp.loadtest.grpc.FetchConnectCommandsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "FetchConnectCommands"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.FetchConnectCommandsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.FetchConnectCommandsResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginServiceMethodDescriptorSupplier("FetchConnectCommands"))
              .build();
        }
      }
    }
    return getFetchConnectCommandsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.InteropRequest,
      com.rgp.loadtest.grpc.InteropResponse> getInteropMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Interop",
      requestType = com.rgp.loadtest.grpc.InteropRequest.class,
      responseType = com.rgp.loadtest.grpc.InteropResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.InteropRequest,
      com.rgp.loadtest.grpc.InteropResponse> getInteropMethod() {
    io.grpc.MethodDescriptor<com.rgp.loadtest.grpc.InteropRequest, com.rgp.loadtest.grpc.InteropResponse> getInteropMethod;
    if ((getInteropMethod = PluginServiceGrpc.getInteropMethod) == null) {
      synchronized (PluginServiceGrpc.class) {
        if ((getInteropMethod = PluginServiceGrpc.getInteropMethod) == null) {
          PluginServiceGrpc.getInteropMethod = getInteropMethod =
              io.grpc.MethodDescriptor.<com.rgp.loadtest.grpc.InteropRequest, com.rgp.loadtest.grpc.InteropResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Interop"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.InteropRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.rgp.loadtest.grpc.InteropResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginServiceMethodDescriptorSupplier("Interop"))
              .build();
        }
      }
    }
    return getInteropMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static PluginServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PluginServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PluginServiceStub>() {
        @java.lang.Override
        public PluginServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PluginServiceStub(channel, callOptions);
        }
      };
    return PluginServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static PluginServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PluginServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PluginServiceBlockingV2Stub>() {
        @java.lang.Override
        public PluginServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PluginServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return PluginServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static PluginServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PluginServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PluginServiceBlockingStub>() {
        @java.lang.Override
        public PluginServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PluginServiceBlockingStub(channel, callOptions);
        }
      };
    return PluginServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static PluginServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PluginServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PluginServiceFutureStub>() {
        @java.lang.Override
        public PluginServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PluginServiceFutureStub(channel, callOptions);
        }
      };
    return PluginServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void connectAndCall(com.rgp.loadtest.grpc.ConnectAndCallRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getConnectAndCallMethod(), responseObserver);
    }

    /**
     */
    default void call(com.rgp.loadtest.grpc.PluginRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.PluginResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCallMethod(), responseObserver);
    }

    /**
     */
    default void disconnect(com.rgp.loadtest.grpc.DisconnectRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDisconnectMethod(), responseObserver);
    }

    /**
     */
    default void disconnectBatch(com.rgp.loadtest.grpc.DisconnectBatchRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDisconnectBatchMethod(), responseObserver);
    }

    /**
     */
    default void findSessionsToRemove(com.rgp.loadtest.grpc.SyncSessionsRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.SyncSessionsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getFindSessionsToRemoveMethod(), responseObserver);
    }

    /**
     */
    default void callBatchInternally(com.rgp.loadtest.grpc.BatchPluginRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.BatchPluginResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getCallBatchInternallyMethod(), responseObserver);
    }

    /**
     */
    default void syncSessions(com.rgp.loadtest.grpc.SyncSessionsRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSyncSessionsMethod(), responseObserver);
    }

    /**
     */
    default void fetchConnectCommands(com.rgp.loadtest.grpc.FetchConnectCommandsRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.FetchConnectCommandsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getFetchConnectCommandsMethod(), responseObserver);
    }

    /**
     */
    default void interop(com.rgp.loadtest.grpc.InteropRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.InteropResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInteropMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service PluginService.
   */
  public static abstract class PluginServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return PluginServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service PluginService.
   */
  public static final class PluginServiceStub
      extends io.grpc.stub.AbstractAsyncStub<PluginServiceStub> {
    private PluginServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PluginServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PluginServiceStub(channel, callOptions);
    }

    /**
     */
    public void connectAndCall(com.rgp.loadtest.grpc.ConnectAndCallRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getConnectAndCallMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void call(com.rgp.loadtest.grpc.PluginRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.PluginResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCallMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void disconnect(com.rgp.loadtest.grpc.DisconnectRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDisconnectMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void disconnectBatch(com.rgp.loadtest.grpc.DisconnectBatchRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDisconnectBatchMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void findSessionsToRemove(com.rgp.loadtest.grpc.SyncSessionsRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.SyncSessionsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getFindSessionsToRemoveMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void callBatchInternally(com.rgp.loadtest.grpc.BatchPluginRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.BatchPluginResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getCallBatchInternallyMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void syncSessions(com.rgp.loadtest.grpc.SyncSessionsRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSyncSessionsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void fetchConnectCommands(com.rgp.loadtest.grpc.FetchConnectCommandsRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.FetchConnectCommandsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getFetchConnectCommandsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void interop(com.rgp.loadtest.grpc.InteropRequest request,
        io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.InteropResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInteropMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service PluginService.
   */
  public static final class PluginServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<PluginServiceBlockingV2Stub> {
    private PluginServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PluginServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PluginServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.rgp.loadtest.grpc.ZmqResponse connectAndCall(com.rgp.loadtest.grpc.ConnectAndCallRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getConnectAndCallMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.PluginResponse call(com.rgp.loadtest.grpc.PluginRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCallMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.ZmqResponse disconnect(com.rgp.loadtest.grpc.DisconnectRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDisconnectMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.ZmqResponse disconnectBatch(com.rgp.loadtest.grpc.DisconnectBatchRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDisconnectBatchMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.SyncSessionsResponse findSessionsToRemove(com.rgp.loadtest.grpc.SyncSessionsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getFindSessionsToRemoveMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.BatchPluginResponse callBatchInternally(com.rgp.loadtest.grpc.BatchPluginRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getCallBatchInternallyMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.ZmqResponse syncSessions(com.rgp.loadtest.grpc.SyncSessionsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSyncSessionsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.FetchConnectCommandsResponse fetchConnectCommands(com.rgp.loadtest.grpc.FetchConnectCommandsRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getFetchConnectCommandsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.InteropResponse interop(com.rgp.loadtest.grpc.InteropRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInteropMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service PluginService.
   */
  public static final class PluginServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<PluginServiceBlockingStub> {
    private PluginServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PluginServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PluginServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.rgp.loadtest.grpc.ZmqResponse connectAndCall(com.rgp.loadtest.grpc.ConnectAndCallRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getConnectAndCallMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.PluginResponse call(com.rgp.loadtest.grpc.PluginRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCallMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.ZmqResponse disconnect(com.rgp.loadtest.grpc.DisconnectRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDisconnectMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.ZmqResponse disconnectBatch(com.rgp.loadtest.grpc.DisconnectBatchRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDisconnectBatchMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.SyncSessionsResponse findSessionsToRemove(com.rgp.loadtest.grpc.SyncSessionsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getFindSessionsToRemoveMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.BatchPluginResponse callBatchInternally(com.rgp.loadtest.grpc.BatchPluginRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getCallBatchInternallyMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.ZmqResponse syncSessions(com.rgp.loadtest.grpc.SyncSessionsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSyncSessionsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.FetchConnectCommandsResponse fetchConnectCommands(com.rgp.loadtest.grpc.FetchConnectCommandsRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getFetchConnectCommandsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.rgp.loadtest.grpc.InteropResponse interop(com.rgp.loadtest.grpc.InteropRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInteropMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service PluginService.
   */
  public static final class PluginServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<PluginServiceFutureStub> {
    private PluginServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PluginServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PluginServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.rgp.loadtest.grpc.ZmqResponse> connectAndCall(
        com.rgp.loadtest.grpc.ConnectAndCallRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getConnectAndCallMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.rgp.loadtest.grpc.PluginResponse> call(
        com.rgp.loadtest.grpc.PluginRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCallMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.rgp.loadtest.grpc.ZmqResponse> disconnect(
        com.rgp.loadtest.grpc.DisconnectRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDisconnectMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.rgp.loadtest.grpc.ZmqResponse> disconnectBatch(
        com.rgp.loadtest.grpc.DisconnectBatchRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDisconnectBatchMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.rgp.loadtest.grpc.SyncSessionsResponse> findSessionsToRemove(
        com.rgp.loadtest.grpc.SyncSessionsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getFindSessionsToRemoveMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.rgp.loadtest.grpc.BatchPluginResponse> callBatchInternally(
        com.rgp.loadtest.grpc.BatchPluginRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getCallBatchInternallyMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.rgp.loadtest.grpc.ZmqResponse> syncSessions(
        com.rgp.loadtest.grpc.SyncSessionsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSyncSessionsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.rgp.loadtest.grpc.FetchConnectCommandsResponse> fetchConnectCommands(
        com.rgp.loadtest.grpc.FetchConnectCommandsRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getFetchConnectCommandsMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.rgp.loadtest.grpc.InteropResponse> interop(
        com.rgp.loadtest.grpc.InteropRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInteropMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CONNECT_AND_CALL = 0;
  private static final int METHODID_CALL = 1;
  private static final int METHODID_DISCONNECT = 2;
  private static final int METHODID_DISCONNECT_BATCH = 3;
  private static final int METHODID_FIND_SESSIONS_TO_REMOVE = 4;
  private static final int METHODID_CALL_BATCH_INTERNALLY = 5;
  private static final int METHODID_SYNC_SESSIONS = 6;
  private static final int METHODID_FETCH_CONNECT_COMMANDS = 7;
  private static final int METHODID_INTEROP = 8;

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
        case METHODID_CONNECT_AND_CALL:
          serviceImpl.connectAndCall((com.rgp.loadtest.grpc.ConnectAndCallRequest) request,
              (io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse>) responseObserver);
          break;
        case METHODID_CALL:
          serviceImpl.call((com.rgp.loadtest.grpc.PluginRequest) request,
              (io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.PluginResponse>) responseObserver);
          break;
        case METHODID_DISCONNECT:
          serviceImpl.disconnect((com.rgp.loadtest.grpc.DisconnectRequest) request,
              (io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse>) responseObserver);
          break;
        case METHODID_DISCONNECT_BATCH:
          serviceImpl.disconnectBatch((com.rgp.loadtest.grpc.DisconnectBatchRequest) request,
              (io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse>) responseObserver);
          break;
        case METHODID_FIND_SESSIONS_TO_REMOVE:
          serviceImpl.findSessionsToRemove((com.rgp.loadtest.grpc.SyncSessionsRequest) request,
              (io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.SyncSessionsResponse>) responseObserver);
          break;
        case METHODID_CALL_BATCH_INTERNALLY:
          serviceImpl.callBatchInternally((com.rgp.loadtest.grpc.BatchPluginRequest) request,
              (io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.BatchPluginResponse>) responseObserver);
          break;
        case METHODID_SYNC_SESSIONS:
          serviceImpl.syncSessions((com.rgp.loadtest.grpc.SyncSessionsRequest) request,
              (io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.ZmqResponse>) responseObserver);
          break;
        case METHODID_FETCH_CONNECT_COMMANDS:
          serviceImpl.fetchConnectCommands((com.rgp.loadtest.grpc.FetchConnectCommandsRequest) request,
              (io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.FetchConnectCommandsResponse>) responseObserver);
          break;
        case METHODID_INTEROP:
          serviceImpl.interop((com.rgp.loadtest.grpc.InteropRequest) request,
              (io.grpc.stub.StreamObserver<com.rgp.loadtest.grpc.InteropResponse>) responseObserver);
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
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getConnectAndCallMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.rgp.loadtest.grpc.ConnectAndCallRequest,
              com.rgp.loadtest.grpc.ZmqResponse>(
                service, METHODID_CONNECT_AND_CALL)))
        .addMethod(
          getCallMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.rgp.loadtest.grpc.PluginRequest,
              com.rgp.loadtest.grpc.PluginResponse>(
                service, METHODID_CALL)))
        .addMethod(
          getDisconnectMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.rgp.loadtest.grpc.DisconnectRequest,
              com.rgp.loadtest.grpc.ZmqResponse>(
                service, METHODID_DISCONNECT)))
        .addMethod(
          getDisconnectBatchMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.rgp.loadtest.grpc.DisconnectBatchRequest,
              com.rgp.loadtest.grpc.ZmqResponse>(
                service, METHODID_DISCONNECT_BATCH)))
        .addMethod(
          getFindSessionsToRemoveMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.rgp.loadtest.grpc.SyncSessionsRequest,
              com.rgp.loadtest.grpc.SyncSessionsResponse>(
                service, METHODID_FIND_SESSIONS_TO_REMOVE)))
        .addMethod(
          getCallBatchInternallyMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.rgp.loadtest.grpc.BatchPluginRequest,
              com.rgp.loadtest.grpc.BatchPluginResponse>(
                service, METHODID_CALL_BATCH_INTERNALLY)))
        .addMethod(
          getSyncSessionsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.rgp.loadtest.grpc.SyncSessionsRequest,
              com.rgp.loadtest.grpc.ZmqResponse>(
                service, METHODID_SYNC_SESSIONS)))
        .addMethod(
          getFetchConnectCommandsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.rgp.loadtest.grpc.FetchConnectCommandsRequest,
              com.rgp.loadtest.grpc.FetchConnectCommandsResponse>(
                service, METHODID_FETCH_CONNECT_COMMANDS)))
        .addMethod(
          getInteropMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.rgp.loadtest.grpc.InteropRequest,
              com.rgp.loadtest.grpc.InteropResponse>(
                service, METHODID_INTEROP)))
        .build();
  }

  private static abstract class PluginServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    PluginServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.rgp.loadtest.grpc.PluginServiceProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("PluginService");
    }
  }

  private static final class PluginServiceFileDescriptorSupplier
      extends PluginServiceBaseDescriptorSupplier {
    PluginServiceFileDescriptorSupplier() {}
  }

  private static final class PluginServiceMethodDescriptorSupplier
      extends PluginServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    PluginServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (PluginServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new PluginServiceFileDescriptorSupplier())
              .addMethod(getConnectAndCallMethod())
              .addMethod(getCallMethod())
              .addMethod(getDisconnectMethod())
              .addMethod(getDisconnectBatchMethod())
              .addMethod(getFindSessionsToRemoveMethod())
              .addMethod(getCallBatchInternallyMethod())
              .addMethod(getSyncSessionsMethod())
              .addMethod(getFetchConnectCommandsMethod())
              .addMethod(getInteropMethod())
              .build();
        }
      }
    }
    return result;
  }
}
