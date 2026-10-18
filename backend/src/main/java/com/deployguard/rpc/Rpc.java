package com.deployguard.rpc;

import com.deployguard.common.Json;
import com.deployguard.common.Msg;
import com.deployguard.core.ConfigBundle;
import io.grpc.MethodDescriptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

// gRPC service contracts. Real gRPC over HTTP/2 (deadlines, status codes) with a JSON codec
// instead of generated protobuf classes.
public final class Rpc {
    private Rpc() {}

    public static final String CONTROLLER_SERVICE = "deployguard.Controller";
    public static final String PEER_SERVICE = "deployguard.Peer";

    // Agent -> leader controller
    public record RegisterRequest(String agentId, String env, String address, long incarnation,
                                  long appliedRevision, long lamport, Map<String, Long> vclock) {}
    public record RegisterResponse(boolean accepted, Integer leaderId, long desiredRevision, String controllerId,
                                   long heartbeatMs, long lamport, Map<String, Long> vclock) {}
    public record HeartbeatRequest(String agentId, String env, long incarnation, long appliedRevision,
                                   long lamport, Map<String, Long> vclock, Long clockOffsetMs, Long rttMs,
                                   List<String> activeFaults, long sentAtMs) {}
    // accepted=false means "I am not the leader": the agent should switch to leaderId (if known).
    public record HeartbeatResponse(boolean accepted, boolean known, Integer leaderId, long desiredRevision,
                                    String controllerId, long lamport, Map<String, Long> vclock,
                                    List<Msg> commands) {}
    public record FetchConfigRequest(String agentId, String env, long appliedRevision) {}
    public record ReportRequest(String agentId, List<Msg> messages) {}
    public record ReportResponse(boolean accepted, Integer leaderId, int stored) {}
    public record TimeRequest(String agentId, long clientSendMs) {}
    public record TimeResponse(long serverTimeMs, String controllerId) {}

    // Controller <-> controller
    public record ElectionMsg(int fromId, long epoch, long lamport, Map<String, Long> vclock) {}
    public record CoordinatorMsg(int leaderId, long epoch, long lamport, Map<String, Long> vclock) {}
    public record LeaderBeat(int leaderId, long epoch, long lamport, Map<String, Long> vclock) {}
    public record Answer(boolean ok, int fromId, long epoch) {}
    // Primary-backup replication: the leader pushes its whole state, tagged with its election epoch.
    public record ReplicateRequest(int leaderId, long epoch, long version, String state) {}
    public record ReplicateResponse(boolean ok, String controllerId, long epoch, Integer leaderView,
                                    long version, long startedAtMs, String reason) {}
    public record PeerCommand(String command, Map<String, Object> args) {}
    public record PeerReply(boolean ok, Map<String, Object> data) {}

    public static <T> MethodDescriptor.Marshaller<T> json(Class<T> type) {
        return new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(T value) {
                return new ByteArrayInputStream(Json.bytes(value));
            }

            @Override
            public T parse(InputStream stream) {
                try {
                    return Json.read(stream.readAllBytes(), type);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    private static <Q, R> MethodDescriptor<Q, R> unary(String service, String method, Class<Q> q, Class<R> r) {
        return MethodDescriptor.<Q, R>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(service, method))
                .setRequestMarshaller(json(q))
                .setResponseMarshaller(json(r))
                .build();
    }

    public static final MethodDescriptor<RegisterRequest, RegisterResponse> REGISTER =
            unary(CONTROLLER_SERVICE, "Register", RegisterRequest.class, RegisterResponse.class);
    public static final MethodDescriptor<HeartbeatRequest, HeartbeatResponse> HEARTBEAT =
            unary(CONTROLLER_SERVICE, "Heartbeat", HeartbeatRequest.class, HeartbeatResponse.class);
    public static final MethodDescriptor<FetchConfigRequest, ConfigBundle> FETCH_CONFIG =
            unary(CONTROLLER_SERVICE, "FetchConfig", FetchConfigRequest.class, ConfigBundle.class);
    public static final MethodDescriptor<ReportRequest, ReportResponse> REPORT =
            unary(CONTROLLER_SERVICE, "Report", ReportRequest.class, ReportResponse.class);
    public static final MethodDescriptor<TimeRequest, TimeResponse> GET_TIME =
            unary(CONTROLLER_SERVICE, "GetTime", TimeRequest.class, TimeResponse.class);

    public static final MethodDescriptor<ElectionMsg, Answer> ELECTION =
            unary(PEER_SERVICE, "Election", ElectionMsg.class, Answer.class);
    public static final MethodDescriptor<CoordinatorMsg, Answer> COORDINATOR =
            unary(PEER_SERVICE, "Coordinator", CoordinatorMsg.class, Answer.class);
    public static final MethodDescriptor<LeaderBeat, Answer> LEADER_BEAT =
            unary(PEER_SERVICE, "LeaderHeartbeat", LeaderBeat.class, Answer.class);
    public static final MethodDescriptor<ReplicateRequest, ReplicateResponse> REPLICATE =
            unary(PEER_SERVICE, "Replicate", ReplicateRequest.class, ReplicateResponse.class);
    public static final MethodDescriptor<PeerCommand, PeerReply> COMMAND =
            unary(PEER_SERVICE, "Command", PeerCommand.class, PeerReply.class);
}
