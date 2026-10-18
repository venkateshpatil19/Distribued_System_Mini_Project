package com.deployguard.controller;

import com.deployguard.common.Json;
import com.deployguard.common.Log;
import com.deployguard.common.Retry;
import com.deployguard.common.VectorClock;
import com.deployguard.controller.StateData.FlagRec;
import com.deployguard.controller.StateData.RolloutRec;
import com.deployguard.core.ConfigBundle;
import com.deployguard.core.ConfigBundle.FlagEnvConfig;
import com.deployguard.core.ConfigBundle.Rule;
import com.deployguard.core.FlagEvaluator;
import com.deployguard.core.RolloutState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// REST + SSE API used by the dashboard. The leader answers; a follower forwards the request to the
// leader over gRPC, and if no leader is reachable it still answers reads from its replica.
public final class ApiServer {
    public static final class ApiError extends RuntimeException {
        public final int status;

        public ApiError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public record Resp(int status, Object body) {}

    record Req(Matcher path, Map<String, String> query, String body, String actor) {
        String p(int i) {
            return URLDecoder.decode(path.group(i), StandardCharsets.UTF_8);
        }

        JsonNode json() {
            if (body == null || body.isBlank()) return Json.obj();
            return Json.tree(body);
        }

        <T> T as(Class<T> t) {
            if (body == null || body.isBlank()) throw new ApiError(400, "Request body is required");
            return Json.read(body, t);
        }

        int qi(String k, int def) {
            try {
                return query.containsKey(k) ? Integer.parseInt(query.get(k)) : def;
            } catch (NumberFormatException e) {
                throw new ApiError(400, k + " must be an integer");
            }
        }
    }

    @FunctionalInterface
    interface Handler {
        Object handle(Req r) throws Exception;
    }

    private record Route(String method, Pattern pattern, Handler h) {}

    record Created(Object body) {}

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");

    private final Ctx ctx;
    private final Rollouts rollouts;
    private final Faults faults;
    private final PeerClient peers;
    private final Snapshots snapshots;
    private final DeadlockDemo deadlocks;
    private final LeaseRace leaseRace;
    private final Scenarios scenarios;
    private final List<Route> routes = new ArrayList<>();
    private HttpServer server;

    public ApiServer(Ctx ctx, Rollouts rollouts, Faults faults, PeerClient peers, Snapshots snapshots,
                     DeadlockDemo deadlocks, LeaseRace leaseRace, Scenarios scenarios) {
        this.ctx = ctx;
        this.rollouts = rollouts;
        this.faults = faults;
        this.peers = peers;
        this.snapshots = snapshots;
        this.deadlocks = deadlocks;
        this.leaseRace = leaseRace;
        this.scenarios = scenarios;
        defineRoutes();
    }

    private void on(String method, String regex, Handler h) {
        routes.add(new Route(method, Pattern.compile("^" + regex + "$"), h));
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 128);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::dispatch);
        server.start();
        Log.info("http_started", "port", port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ---------- HTTP plumbing ----------

    private void dispatch(HttpExchange ex) {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        try {
            if ("OPTIONS".equals(method)) {
                cors(ex);
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            if ("GET".equals(method) && "/api/stream".equals(path)) {
                cors(ex);
                ctx.sse.attach(ex);
                return;
            }
            String body = null;
            if (!"GET".equals(method)) {
                try (InputStream in = ex.getRequestBody()) {
                    byte[] b = in.readNBytes(256 * 1024 + 1);
                    if (b.length > 256 * 1024) throw new ApiError(413, "Request body too large");
                    body = new String(b, StandardCharsets.UTF_8);
                }
            }
            String actor = actor(ex.getRequestHeaders().getFirst("X-Actor"));
            boolean local = !path.startsWith("/api/") || path.equals("/api/build");
            // During an election there is briefly no ready leader; wait for it instead of failing at once.
            if (!local && ctx.isolated()) throw new ApiError(503, ctx.id + " is cut off from the other controllers (simulated partition)");
            long deadline = System.currentTimeMillis() + 3000;
            while (!local && !ctx.isLeader()) {
                Resp fwd = forward(method, path, query, body, actor);
                if (fwd != null) {
                    sendRaw(ex, fwd.status(), (String) fwd.body(), false);
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    if (!"GET".equals(method)) throw new ApiError(503, "No leader is reachable right now; retry in a few seconds");
                    // Reads still work from this follower's replica, marked as possibly stale.
                    Resp r = handle(method, path, query, body, actor);
                    sendRaw(ex, r.status(), Json.write(r.body()), true);
                    return;
                }
                Retry.sleepQuietly(200);
            }
            Resp r = handle(method, path, query, body, actor);
            sendRaw(ex, r.status(), Json.write(r.body()), false);
        } catch (ApiError e) {
            sendRaw(ex, e.status, Json.write(Map.of("error", e.getMessage())), false);
        } catch (Exception e) {
            Log.error("api_error", e, "path", path);
            sendRaw(ex, 500, Json.write(Map.of("error", "Internal error: " + e.getClass().getSimpleName())), false);
        }
    }

    // Sends the request to the leader over gRPC. Returns null when there is no reachable leader.
    private Resp forward(String method, String path, String query, String body, String actor) {
        Integer leader = ctx.leaderNumericId();
        if (leader == null || leader == ctx.numericId || peers == null) return null;
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("method", method);
            args.put("path", path);
            args.put("query", query == null ? "" : query);
            args.put("body", body == null ? "" : body);
            args.put("actor", actor);
            Map<String, Object> d = peers.command(leader, "api", args, 20_000).data();
            return new Resp(((Number) d.get("status")).intValue(), String.valueOf(d.get("body")));
        } catch (RuntimeException e) {
            Log.warn("forward_failed", "leader", leader, "error", e.getMessage());
            return null;
        }
    }

    // Runs one request on this controller and returns status and body; also used for forwarded requests.
    public Resp handle(String method, String path, String rawQuery, String body, String actor) {
        try {
            boolean pathMatched = false;
            for (Route r : routes) {
                Matcher m = r.pattern.matcher(path);
                if (!m.matches()) continue;
                pathMatched = true;
                if (!r.method.equals(method)) continue;
                Object out = r.h.handle(new Req(m, query(rawQuery), body, actor));
                if (out instanceof Created c) return new Resp("POST".equals(method) ? 201 : 200, c.body);
                return new Resp(200, out);
            }
            throw new ApiError(pathMatched ? 405 : 404, pathMatched ? "Method not allowed" : "No route for " + path);
        } catch (ApiError e) {
            return new Resp(e.status, Map.of("error", e.getMessage()));
        } catch (StateStore.FencingRejected e) {
            return new Resp(409, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return new Resp(400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            Log.error("api_error", e, "path", path);
            return new Resp(500, Map.of("error", "Internal error: " + e.getClass().getSimpleName()));
        }
    }

    static String actor(String a) {
        if (a == null || a.isBlank()) return "dashboard";
        String clean = a.replaceAll("[^A-Za-z0-9 ._@-]", "");
        return clean.isBlank() ? "dashboard" : clean.substring(0, Math.min(40, clean.length()));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            q.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static void cors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-Actor");
    }

    private void sendRaw(HttpExchange ex, int status, String json, boolean degraded) {
        try {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            cors(ex);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("X-Served-By", ctx.id);
            if (degraded) ex.getResponseHeaders().set("X-Degraded", "replica-read");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (IOException e) {
            Log.warn("http_write_failed", "error", e.getMessage());
        } finally {
            ex.close();
        }
    }

    // ---------------- routes ----------------

    private void defineRoutes() {
        on("GET", "/healthz", r -> Map.of("status", "ok", "node", ctx.id));
        on("GET", "/readyz", r -> {
            if (ctx.leaderNumericId() == null) throw new ApiError(503, "not ready: no leader elected yet");
            return Map.of("status", "ready", "node", ctx.id, "leader", "controller-" + ctx.leaderNumericId());
        });
        on("GET", "/api/build", r -> build());
        on("GET", "/api/overview", r -> overview());

        on("GET", "/api/environments", r -> ctx.store.read(st -> StateStore.viewAll(st.environments.values())));
        on("POST", "/api/environments", r -> {
            JsonNode b = r.json();
            String key = b.path("key").asText("");
            if (!KEY.matcher(key).matches()) throw new ApiError(400, "key must be 2-63 lowercase letters, digits or hyphens");
            String name = b.path("name").asText(key).trim();
            String finalName = name.isEmpty() ? key : name;
            ctx.store.write(st -> {
                StateStore.createEnvironment(st, key, finalName);
                return StateStore.newRevision(st, "environment created: " + key, r.actor(), null, key, null, null, null);
            });
            ctx.events.record("ENVIRONMENT_CREATED", "ok", null, Map.of("env", key, "actor", r.actor()));
            return new Created(Map.of("key", key, "name", finalName));
        });

        on("GET", "/api/flags", r -> ctx.store.read(StateStore::flagViews));
        on("POST", "/api/flags", this::createFlag);
        on("GET", "/api/flags/([a-z0-9-]+)", r -> ctx.store.read(st -> StateStore.flagView(st, StateStore.flag(st, r.p(1)))));
        on("PATCH", "/api/flags/([a-z0-9-]+)", r -> {
            JsonNode b = r.json();
            String name = b.path("name").asText("").trim();
            if (name.isEmpty() || name.length() > 120) throw new ApiError(400, "name must be 1..120 characters");
            String desc = b.path("description").asText("");
            if (desc.length() > 1000) throw new ApiError(400, "description is limited to 1000 characters");
            Map<String, Object> out = ctx.store.write(st -> {
                FlagRec f = StateStore.flag(st, r.p(1));
                f.name = name;
                f.description = desc;
                f.updatedAt = System.currentTimeMillis();
                return StateStore.flagView(st, f);
            });
            ctx.events.record("FLAG_UPDATED", "ok", null, Map.of("flag", r.p(1), "actor", r.actor()));
            return out;
        });
        on("PUT", "/api/flags/([a-z0-9-]+)/envs/([a-z0-9-]+)", this::updateConfig);
        on("POST", "/api/flags/([a-z0-9-]+)/evaluate", this::evaluate);
        on("POST", "/api/flags/([a-z0-9-]+)/preview", this::preview);
        on("GET", "/api/flags/([a-z0-9-]+)/revisions", r -> ctx.store.read(st -> StateStore.revisionViews(st, r.p(1), r.qi("limit", 50))));
        on("GET", "/api/revisions", r -> ctx.store.read(st -> StateStore.revisionViews(st, null, r.qi("limit", 50))));

        on("GET", "/api/rollouts", r -> rollouts.list(r.qi("limit", 50)));
        on("POST", "/api/rollouts", r -> new Created(rollouts.create(r.as(Rollouts.CreateRequest.class), r.actor())));
        on("GET", "/api/rollouts/(\\d+)", r -> rollouts.get(Long.parseLong(r.p(1))).orElseThrow(() -> new ApiError(404, "Rollout not found")));
        on("POST", "/api/rollouts/(\\d+)/(promote|pause|resume|abort|rollback)", r -> rollouts.act(Long.parseLong(r.p(1)),
                RolloutState.Action.valueOf(r.p(2).toUpperCase()), r.actor(), r.json().path("reason").asText(null)));
        on("GET", "/api/rollouts/(\\d+)/metrics", r -> {
            RolloutRec ro = ctx.store.read(st -> st.rollouts.get(Long.parseLong(r.p(1))));
            if (ro == null) throw new ApiError(404, "Rollout not found");
            return ctx.store.series(ro.flagKey, ro.envKey, r.qi("minutes", 15));
        });

        on("GET", "/api/agents", r -> ctx.store.agents(ctx.fd, "true".equals(r.query().get("includeRetired"))));
        on("POST", "/api/agents/([A-Za-z0-9._-]+)/faults", r -> faults.agentFault(r.p(1), Json.map(r.json())));
        on("POST", "/api/faults", r -> faults.agentFault(null, Json.map(r.json())));
        on("GET", "/api/traffic", r -> ctx.store.read(st -> new LinkedHashMap<>(st.traffic)));
        on("PUT", "/api/traffic", r -> {
            JsonNode b = r.json();
            boolean enabled = b.path("enabled").asBoolean(true);
            int rps = b.path("rps").asInt(40);
            if (rps < 1 || rps > 500) throw new ApiError(400, "rps must be 1..500 per agent");
            Map<String, Object> t = new LinkedHashMap<>(Map.of("enabled", enabled, "rps", rps));
            ctx.store.write(st -> st.traffic = t);
            ctx.sendControl(null, "TRAFFIC", t);
            ctx.events.record("TRAFFIC_UPDATED", "ok", null, Map.of("enabled", enabled, "rps", rps, "actor", r.actor()), true);
            return t;
        });

        on("GET", "/api/controllers", r -> controllers());
        on("POST", "/api/controllers/(controller-\\d+)/faults", this::controllerFault);

        on("GET", "/api/topology", r -> topology());
        on("GET", "/api/events", r -> ctx.store.events(r.qi("limit", 100), r.query().get("type"), r.query().get("node")));
        on("GET", "/api/events/compare", this::compareEvents);
        on("GET", "/api/clocks", r -> clocks());
        on("GET", "/api/leases", r -> ctx.leases.list());
        on("GET", "/api/metrics/series", r -> ctx.store.series(r.query().getOrDefault("flag", "__all__"),
                r.query().getOrDefault("env", "*"), r.qi("minutes", 15)));

        on("POST", "/api/experiments/snapshot", r -> snapshots.take(8000));
        on("GET", "/api/snapshots", r -> snapshots.list());
        on("GET", "/api/snapshots/([A-Za-z0-9-]+)", r -> snapshots.get(r.p(1)));
        on("POST", "/api/experiments/deadlock", r -> deadlocks.run(r.json().path("scenario").asText("two-way")));
        on("POST", "/api/experiments/lease-race", r -> {
            JsonNode b = r.json();
            long ttl = b.path("ttlMs").asLong(1500);
            long pause = b.path("pauseMs").asLong(3000);
            if (ttl < 500 || ttl > 10_000 || pause < 0 || pause > 15_000) throw new ApiError(400, "ttlMs 500..10000, pauseMs 0..15000");
            return leaseRace.run(ttl, pause);
        });
        on("GET", "/api/experiments/runs", r -> scenarios.runs(r.qi("limit", 30)));
        on("POST", "/api/experiments/runs", r -> {
            JsonNode b = r.json();
            return new Created(scenarios.start(b.path("scenario").asText(""), b.has("params") ? Json.map(b.get("params")) : Map.of()));
        });
        on("POST", "/api/experiments/runs/(\\d+)/replay", r -> new Created(scenarios.replay(Long.parseLong(r.p(1)))));
        on("GET", "/api/experiments/recovery", r -> recovery());
    }

    private Map<String, Object> build() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", ctx.id);
        m.put("version", ctx.version);
        m.put("buildTime", System.getenv().getOrDefault("DG_BUILD_TIME", "unknown"));
        m.put("startedAt", StateStore.iso(ctx.startedAtMs));
        return m;
    }

    private Object createFlag(Req r) {
        JsonNode b = r.json();
        String key = b.path("key").asText("");
        if (!KEY.matcher(key).matches()) throw new ApiError(400, "key must be 2-63 lowercase letters, digits or hyphens");
        String name = b.path("name").asText("").trim();
        if (name.isEmpty() || name.length() > 120) throw new ApiError(400, "name must be 1..120 characters");
        String type = b.path("type").asText("boolean");
        String on = b.has("onValue") ? Json.write(b.get("onValue")) : ("boolean".equals(type) ? "true" : null);
        String off = b.has("offValue") ? Json.write(b.get("offValue")) : StateStore.defaultOff(type);
        for (String[] v : new String[][]{{"onValue", on}, {"offValue", off}}) {
            String err = FlagEvaluator.validateValue(type, v[1]);
            if (err != null) throw new ApiError(400, v[0] + ": " + err);
        }
        String desc = b.path("description").asText("");
        ConfigBundle bundle = ctx.store.write(st -> {
            StateStore.createFlag(st, key, name, desc, type, on, off);
            return StateStore.newRevision(st, "flag created: " + key, r.actor(), key, null, null, null, null);
        });
        ctx.events.record("FLAG_CREATED", "ok", bundle.revision(), Map.of("flag", key, "type", type, "actor", r.actor()));
        return new Created(ctx.store.read(st -> StateStore.flagView(st, st.flags.get(key))));
    }

    static FlagEnvConfig parseConfig(JsonNode b, String type, FlagEnvConfig current) {
        boolean enabled = b.has("enabled") ? b.get("enabled").asBoolean() : current.enabled();
        int pct = b.has("rolloutPercent") ? b.get("rolloutPercent").asInt(-1) : current.rolloutPercent();
        if (pct < 0 || pct > 100) throw new ApiError(400, "rolloutPercent must be 0..100");
        String on = b.has("onValue") ? Json.write(b.get("onValue")) : current.onValue();
        String off = b.has("offValue") ? Json.write(b.get("offValue")) : current.offValue();
        for (String[] v : new String[][]{{"onValue", on}, {"offValue", off}}) {
            String err = FlagEvaluator.validateValue(type, v[1]);
            if (err != null) throw new ApiError(400, v[0] + ": " + err);
        }
        List<Rule> rules = current.rules();
        if (b.has("rules")) {
            if (!b.get("rules").isArray() || b.get("rules").size() > 50) throw new ApiError(400, "rules must be an array of at most 50 rules");
            rules = Json.M.convertValue(b.get("rules"), new TypeReference<List<Rule>>() {});
            for (int i = 0; i < rules.size(); i++) {
                String err = FlagEvaluator.validateRule(rules.get(i));
                if (err != null) throw new ApiError(400, "rule " + (i + 1) + ": " + err);
            }
        }
        return new FlagEnvConfig(enabled, rules, pct, on, off);
    }

    // Manual change: blocked while a rollout owns this flag/env, and fenced like every mutation.
    private Object updateConfig(Req r) {
        String flag = r.p(1);
        String env = r.p(2);
        JsonNode b = r.json();
        String reason = b.path("reason").asText("manual edit").trim();
        String resource = StateStore.resource(flag, env);
        Leases.Lease lease = ctx.acquireWithin(resource, ctx.holder("config-edit"), 10_000, 2_000)
                .orElseThrow(() -> new ApiError(409, "Another operation holds the lease on " + resource + "; retry shortly"));
        try {
            ConfigBundle bundle = ctx.store.write(st -> {
                for (RolloutRec x : st.rollouts.values()) {
                    if (x.flagKey.equals(flag) && x.envKey.equals(env) && Rollouts.active(x)) {
                        throw new ApiError(409, "A rollout is active for this flag and environment. Use the rollout controls, or abort it first.");
                    }
                }
                FlagRec f = StateStore.flag(st, flag);
                FlagEnvConfig next = parseConfig(b, f.type, StateStore.envConfig(st, flag, env));
                return StateStore.writeConfig(st, flag, env, next, reason.isEmpty() ? "manual edit" : reason, r.actor(), lease.token());
            });
            ctx.events.record("CONFIG_CHANGED", "ok", bundle.revision(), Map.of("flag", flag, "env", env, "actor", r.actor(),
                    "fenceToken", lease.token(), "reason", reason));
            return Map.of("revision", bundle.revision(), "flag", ctx.store.read(st -> StateStore.flagView(st, st.flags.get(flag))));
        } finally {
            ctx.releaseQuietly(lease);
        }
    }

    private FlagEnvConfig draftConfig(Req r, JsonNode b, String env) {
        return ctx.store.read(st -> {
            FlagRec f = StateStore.flag(st, r.p(1));
            FlagEnvConfig cur = StateStore.envConfig(st, r.p(1), env);
            return b.has("config") ? parseConfig(b.get("config"), f.type, cur) : cur;
        });
    }

    private Object evaluate(Req r) {
        JsonNode b = r.json();
        String env = b.path("env").asText("production");
        Map<String, String> context = b.has("context") ? Json.M.convertValue(b.get("context"), new TypeReference<Map<String, String>>() {}) : Map.of();
        return FlagEvaluator.evaluate(r.p(1), draftConfig(r, b, env), context);
    }

    // Estimates exposure for a draft configuration over synthetic users (labelled as a simulation).
    private Object preview(Req r) {
        JsonNode b = r.json();
        String env = b.path("env").asText("production");
        int n = Math.max(100, Math.min(b.path("sampleSize").asInt(2000), 20000));
        FlagEnvConfig cfg = draftConfig(r, b, env);
        String[] countries = {"IN", "US", "DE", "GB", "BR", "JP"};
        String[] plans = {"free", "pro", "enterprise"};
        int on = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Map<String, String> u = Map.of("userId", "u" + i, "country", countries[i % countries.length], "plan", plans[(i / 7) % plans.length]);
            FlagEvaluator.Evaluation e = FlagEvaluator.evaluate(r.p(1), cfg, u);
            if ("on".equals(e.variant())) on++;
            String k = e.ruleIndex() != null ? "rule " + (e.ruleIndex() + 1) : e.reason().split(":")[0];
            reasons.merge(k, 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sampleSize", n);
        out.put("onCount", on);
        out.put("onPercent", Math.round(on * 1000.0 / n) / 10.0);
        out.put("reasons", reasons);
        out.put("simulated", true);
        return out;
    }

    private Object controllerFault(Req r) {
        String id = r.p(1);
        Map<String, Object> f = Json.map(r.json());
        if (id.equals(ctx.id)) return faults.controllerFault(f);
        int num = Integer.parseInt(id.substring("controller-".length()));
        if (!peers.targets().containsKey(num)) throw new ApiError(404, "Unknown controller " + id);
        try {
            Map<String, Object> args = new HashMap<>(f);
            args.put("remote", true);
            Map<String, Object> ev = peers.command(num, "fault", args, 5000).data();
            ctx.events.record("FAULT_INJECTED", "injected", null, ev, true);
            return ev;
        } catch (RuntimeException e) {
            throw new ApiError(502, id + " did not accept the command: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Object compareEvents(Req r) {
        String a = r.query().get("a");
        String b = r.query().get("b");
        if (a == null || b == null) throw new ApiError(400, "query parameters a and b (event ids) are required");
        Map<String, Object> ea = ctx.store.event(a);
        Map<String, Object> eb = ctx.store.event(b);
        if (ea == null) throw new ApiError(404, "event a not found");
        if (eb == null) throw new ApiError(404, "event b not found");
        Map<String, Long> va = Json.M.convertValue(ea.get("vclock"), new TypeReference<Map<String, Long>>() {});
        Map<String, Long> vb = Json.M.convertValue(eb.get("vclock"), new TypeReference<Map<String, Long>>() {});
        VectorClock.Relation rel = VectorClock.compare(va, vb);
        long la = ((Number) ea.get("lamport")).longValue();
        long lb = ((Number) eb.get("lamport")).longValue();
        String explain = switch (rel) {
            case BEFORE -> "a happened before b: every entry of a is <= b and at least one is smaller";
            case AFTER -> "b happened before a";
            case EQUAL -> "identical vector clocks (same event)";
            case CONCURRENT -> "concurrent: neither vector dominates, so no causal order exists even though Lamport times are "
                    + la + " and " + lb;
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", ea);
        out.put("b", eb);
        out.put("relation", rel.name());
        out.put("lamportOrder", Long.compare(la, lb));
        out.put("explanation", explain);
        return out;
    }

    // Controller list as seen by this controller: itself plus what replication replies told the leader.
    private List<Map<String, Object>> controllers() {
        long now = System.currentTimeMillis();
        Integer leader = ctx.leaderNumericId();
        List<Map<String, Object>> list = new ArrayList<>();
        Map<Integer, Map<String, Object>> byId = new java.util.TreeMap<>();
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("id", ctx.id);
        self.put("numericId", ctx.numericId);
        self.put("lastSeen", StateStore.iso(now));
        self.put("leaderId", leader == null ? null : "controller-" + leader);
        self.put("epoch", ctx.election == null ? 0 : ctx.election.epoch());
        self.put("isLeader", ctx.isLeader());
        self.put("startedAt", StateStore.iso(ctx.startedAtMs));
        self.put("version", ctx.version);
        self.put("ageMs", 0);
        self.put("alive", true);
        self.put("self", true);
        byId.put(ctx.numericId, self);
        if (peers != null) {
            for (int peer : peers.targets().keySet()) {
                Ctx.PeerStatus ps = ctx.peerStatus.get(peer);
                Map<String, Object> m = new LinkedHashMap<>();
                long age = ps == null ? Long.MAX_VALUE : now - ps.lastAckMs();
                m.put("id", "controller-" + peer);
                m.put("numericId", peer);
                m.put("lastSeen", ps == null ? null : StateStore.iso(ps.lastAckMs()));
                m.put("leaderId", ps == null || ps.leaderView() == null ? null : "controller-" + ps.leaderView());
                m.put("epoch", ps == null ? 0 : ps.epoch());
                m.put("isLeader", false);
                m.put("startedAt", ps == null ? null : StateStore.iso(ps.startedAtMs()));
                m.put("version", ctx.version);
                m.put("ageMs", ps == null ? -1 : age);
                // Only the leader hears from followers; a follower cannot tell, so it reports them as unknown/down.
                m.put("alive", ps != null && age < 5000);
                m.put("self", false);
                byId.put(peer, m);
            }
        }
        list.addAll(byId.values());
        return list;
    }

    private Map<String, Object> overview() {
        Map<String, Object> o = new LinkedHashMap<>();
        Integer leader = ctx.leaderNumericId();
        o.put("servedBy", ctx.id);
        o.put("leader", leader == null ? null : "controller-" + leader);
        o.put("epoch", ctx.election == null ? 0 : ctx.election.epoch());
        o.put("controllers", controllers());
        List<Map<String, Object>> agents = ctx.store.agents(ctx.fd, false);
        long latest = ctx.store.read(StateStore::latestRevision);
        Map<String, Integer> byHealth = new LinkedHashMap<>();
        int live = 0;
        int converged = 0;
        for (Map<String, Object> a : agents) {
            byHealth.merge((String) a.get("health"), 1, Integer::sum);
            if (Boolean.TRUE.equals(a.get("live"))) {
                live++;
                if (((Number) a.get("appliedRevision")).longValue() >= latest) converged++;
            }
        }
        o.put("agents", Map.of("total", agents.size(), "live", live, "converged", converged, "byHealth", byHealth));
        o.put("latestRevision", latest);
        o.put("activeRollouts", ctx.store.read(st -> st.rollouts.values().stream().filter(Rollouts::active).map(x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.id);
            m.put("flagKey", x.flagKey);
            m.put("envKey", x.envKey);
            m.put("state", x.state);
            m.put("currentStage", x.currentStage);
            m.put("stages", x.stages);
            m.put("lastVerdict", x.lastVerdict);
            m.put("lastDetail", x.lastDetail);
            return m;
        }).toList()));
        o.put("traffic", ctx.store.totals("__all__", System.currentTimeMillis() - 60_000));
        o.put("trafficSetting", ctx.store.read(st -> new LinkedHashMap<>(st.traffic)));
        o.put("recentEvents", ctx.store.events(12, null, null));
        Object lastEvent = ctx.store.events(1, null, null).stream().findFirst().map(e -> e.get("recordedAt")).orElse(null);
        o.put("lastUpdated", lastEvent);
        long now = System.currentTimeMillis();
        long inSync = ctx.peerStatus.values().stream().filter(p -> now - p.lastAckMs() < 3000).count();
        o.put("storage", Map.of("stateVersion", ctx.store.version(), "followersInSync", inSync,
                "followers", peers == null ? 0 : peers.targets().size()));
        o.put("duplicatesSuppressed", ctx.events.duplicatesSuppressed.get());
        o.put("sseClients", ctx.sse.clientCount());
        o.put("build", build());
        return o;
    }

    private Map<String, Object> topology() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Integer leader = ctx.leaderNumericId();
        List<Map<String, Object>> ctrls = controllers();
        for (Map<String, Object> c : ctrls) {
            String id = (String) c.get("id");
            boolean alive = Boolean.TRUE.equals(c.get("alive"));
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", id);
            n.put("kind", "controller");
            n.put("label", id);
            n.put("status", alive ? "up" : "down");
            n.put("leader", leader != null && id.equals("controller-" + leader));
            n.put("epoch", c.get("epoch"));
            nodes.add(n);
            Map<String, Object> store = new LinkedHashMap<>();
            store.put("id", id + "-state");
            store.put("kind", "storage");
            store.put("label", "state " + id.substring("controller-".length()) + ".json");
            store.put("status", alive ? "up" : "down");
            nodes.add(store);
            edges.add(Map.of("from", id, "to", id + "-state", "kind", "file"));
        }
        for (Map<String, Object> c : ctrls) {
            String id = (String) c.get("id");
            if (leader != null && !id.equals("controller-" + leader) && Boolean.TRUE.equals(c.get("alive"))) {
                edges.add(Map.of("from", "controller-" + leader, "to", id, "kind", "grpc-peer"));
            }
        }
        for (Map<String, Object> a : ctx.store.agents(ctx.fd, false)) {
            String id = (String) a.get("id");
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", id);
            n.put("kind", "agent");
            n.put("label", id);
            n.put("status", a.get("health"));
            n.put("env", a.get("envKey"));
            n.put("appliedRevision", a.get("appliedRevision"));
            n.put("faults", a.get("activeFaults"));
            nodes.add(n);
            if (Boolean.TRUE.equals(a.get("live")) && a.get("lastController") != null) {
                edges.add(Map.of("from", id, "to", a.get("lastController"), "kind", "grpc"));
            }
        }
        return Map.of("nodes", nodes, "edges", edges, "servedBy", ctx.id);
    }

    private Map<String, Object> clocks() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("self", Map.of("node", ctx.id, "lamport", ctx.clock.lamport(), "vclock", ctx.clock.vector(),
                "wallMs", System.currentTimeMillis()));
        List<Map<String, Object>> sync = new ArrayList<>();
        for (Map<String, Object> a : ctx.store.agents(ctx.fd, false)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("agentId", a.get("id"));
            s.put("offsetMs", a.get("clockOffsetMs"));
            s.put("rttMs", a.get("rttMs"));
            Object rtt = a.get("rttMs");
            s.put("errorBoundMs", rtt == null ? null : ((Number) rtt).longValue() / 2.0);
            sync.add(s);
        }
        out.put("cristian", sync);
        out.put("events", ctx.store.events(40, null, null));
        return out;
    }

    // Recovery metrics computed from the replicated event log and rollout records.
    private Map<String, Object> recovery() {
        List<Map<String, Object>> events = ctx.store.events(1000, null, null);
        List<Map<String, Object>> elections = new ArrayList<>();
        List<Map<String, Object>> agentRecoveries = new ArrayList<>();
        for (Map<String, Object> e : events) {
            String type = String.valueOf(e.get("type"));
            String at = String.valueOf(e.get("recordedAt"));
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) e.get("data");
            if ("LEADER_ELECTED".equals(type) && elections.size() < 15) {
                Long started = null;
                long electedMs = java.time.Instant.parse(at).toEpochMilli();
                for (Map<String, Object> s : events) {
                    if (!"ELECTION_STARTED".equals(s.get("type"))) continue;
                    long t = java.time.Instant.parse(String.valueOf(s.get("recordedAt"))).toEpochMilli();
                    if (t <= electedMs && electedMs - t <= 20_000 && (started == null || t < started)) started = t;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("leader", e.get("nodeId"));
                m.put("electedAt", at);
                m.put("durationMs", started == null ? null : electedMs - started);
                m.put("reason", data == null ? null : data.get("reason"));
                elections.add(m);
            }
            if ("AGENT_RECOVERED".equals(type) && agentRecoveries.size() < 15 && data != null) {
                Object agent = data.get("agentId");
                long recMs = java.time.Instant.parse(at).toEpochMilli();
                Long down = null;
                for (Map<String, Object> s : events) {
                    String t = String.valueOf(s.get("type"));
                    if (!t.equals("AGENT_SUSPECTED") && !t.equals("AGENT_DISCONNECTED")) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> sd = (Map<String, Object>) s.get("data");
                    if (sd == null || !String.valueOf(agent).equals(String.valueOf(sd.get("agentId")))) continue;
                    long ts = java.time.Instant.parse(String.valueOf(s.get("recordedAt"))).toEpochMilli();
                    if (ts < recMs && (down == null || ts > down)) down = ts;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("agentId", agent);
                m.put("recoveredAt", at);
                m.put("outageMs", down == null ? null : recMs - down);
                agentRecoveries.add(m);
            }
        }
        List<Map<String, Object>> rollbacks = ctx.store.read(st -> {
            List<Map<String, Object>> out = new ArrayList<>();
            List<RolloutRec> all = new ArrayList<>(st.rollouts.values());
            for (int i = all.size() - 1; i >= 0 && out.size() < 15; i--) {
                RolloutRec x = all.get(i);
                if (x.rollbackRevision == null) continue;
                long lastAck = 0;
                for (Map<String, Object> e : st.events) {
                    if ("CONFIG_APPLIED".equals(e.get("type")) && e.get("revision") instanceof Number n && n.longValue() == x.rollbackRevision) {
                        lastAck = Math.max(lastAck, java.time.Instant.parse(String.valueOf(e.get("recordedAt"))).toEpochMilli());
                    }
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.id);
                m.put("flagKey", x.flagKey);
                m.put("envKey", x.envKey);
                m.put("rollbackReason", x.rollbackReason);
                m.put("createdAt", StateStore.iso(x.createdAt));
                m.put("breachDetectedAt", x.breachDetectedAt == null ? null : StateStore.iso(x.breachDetectedAt));
                m.put("detectMs", x.breachDetectedAt == null ? null : x.breachDetectedAt - x.createdAt);
                m.put("convergeMs", x.breachDetectedAt == null || lastAck == 0 ? null : lastAck - x.breachDetectedAt);
                out.add(m);
            }
            return out;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("elections", elections);
        out.put("rollbacks", rollbacks);
        out.put("agentRecoveries", agentRecoveries);
        return out;
    }
}
