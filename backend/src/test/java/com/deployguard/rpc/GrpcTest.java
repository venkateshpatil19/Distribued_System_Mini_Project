package com.deployguard.rpc;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcTest {

    @Test
    void grpcRoundTripWithJsonCodecAndDeadline() throws Exception {
        ServerServiceDefinition svc = ServerServiceDefinition.builder(Rpc.CONTROLLER_SERVICE)
                .addMethod(Rpc.GET_TIME, ServerCalls.asyncUnaryCall((Rpc.TimeRequest req, io.grpc.stub.StreamObserver<Rpc.TimeResponse> obs) -> {
                    if ("slow".equals(req.agentId())) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if ("bad".equals(req.agentId())) {
                        obs.onError(Status.INVALID_ARGUMENT.withDescription("bad agent").asRuntimeException());
                        return;
                    }
                    obs.onNext(new Rpc.TimeResponse(req.clientSendMs() + 1000, "controller-test"));
                    obs.onCompleted();
                }))
                .build();
        Server server = NettyServerBuilder.forPort(0).addService(svc).build().start();
        ManagedChannel ch = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
        try {
            Rpc.TimeResponse r = ClientCalls.blockingUnaryCall(ch, Rpc.GET_TIME, CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
                    new Rpc.TimeRequest("a1", 5000));
            assertEquals(6000, r.serverTimeMs());
            assertEquals("controller-test", r.controllerId());
            StatusRuntimeException late = assertThrows(StatusRuntimeException.class, () -> ClientCalls.blockingUnaryCall(ch, Rpc.GET_TIME,
                    CallOptions.DEFAULT.withDeadlineAfter(100, TimeUnit.MILLISECONDS), new Rpc.TimeRequest("slow", 0)));
            assertEquals(Status.Code.DEADLINE_EXCEEDED, late.getStatus().getCode());
            StatusRuntimeException bad = assertThrows(StatusRuntimeException.class, () -> ClientCalls.blockingUnaryCall(ch, Rpc.GET_TIME,
                    CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS), new Rpc.TimeRequest("bad", 0)));
            assertEquals(Status.Code.INVALID_ARGUMENT, bad.getStatus().getCode());
        } finally {
            ch.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    void grpcToUnreachablePeerFailsFast() {
        ManagedChannel ch = ManagedChannelBuilder.forAddress("localhost", 1).usePlaintext().build();
        try {
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> ClientCalls.blockingUnaryCall(ch, Rpc.GET_TIME,
                    CallOptions.DEFAULT.withDeadlineAfter(500, TimeUnit.MILLISECONDS), new Rpc.TimeRequest("x", 0)));
            assertTrue(e.getStatus().getCode() == Status.Code.UNAVAILABLE || e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED);
        } finally {
            ch.shutdownNow();
        }
    }
}
