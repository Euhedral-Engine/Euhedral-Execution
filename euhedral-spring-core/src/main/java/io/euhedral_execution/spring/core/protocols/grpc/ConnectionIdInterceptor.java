package io.euhedral_execution.spring.core.protocols.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.net.SocketAddress;
import org.springframework.stereotype.Component;

@Component
public class ConnectionIdInterceptor implements ServerInterceptor {
    public static final Context.Key<String> CONN_ID_KEY = Context.key("connectionId");

    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> serverCall,
            Metadata metadata, ServerCallHandler<ReqT, RespT> serverCallHandler) {
        SocketAddress address = serverCall.getAttributes()
                .get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);

        Context context = Context.current();
        if(address != null) {
            context = context.withValue(CONN_ID_KEY, address.toString());
        }

        return Contexts.interceptCall(context, serverCall, metadata, serverCallHandler);
    }
}
