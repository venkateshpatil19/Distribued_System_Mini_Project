package com.deployguard.controller;

import com.deployguard.common.Json;
import com.deployguard.common.Log;
import com.deployguard.common.Msg;
import com.deployguard.controller.StateData.AgentRec;
import com.deployguard.controller.StateData.EnvCfg;
import com.deployguard.controller.StateData.FlagRec;
import com.deployguard.controller.StateData.RevisionRec;
import com.deployguard.controller.StateData.RolloutRec;
import com.deployguard.core.ConfigBundle;
import com.deployguard.core.ConfigBundle.FlagEnvConfig;
import com.deployguard.core.ConfigBundle.FlagSnapshot;
import com.deployguard.core.FailureDetector;
import com.deployguard.core.Histogram;
import com.deployguard.rpc.Rpc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

// In-memory state with JSON-file persistence. All access goes through read/write so it is thread-safe.
// Metrics are kept only in memory: they are high-volume and short-lived.
public final class StateStore {
    public static final int MAX_EVENTS = 1500;
    public static final int MAX_REVISIONS = 500;

    public static final class FencingRejected extends RuntimeException {
        FencingRejected(String resource, long presented, long current) {
            super("fencing token " + presented + " rejected for " + resource + " (latest accepted token is " + current + ")");
        }
    }

    public record WindowStats(long requests, long errors, long p95Ms) {}

    private record MetricRow(String id, long at, String agent, String flag, String env, long revision,
                             String variant, long requests, long errors, long[] hist) {}

    private final Path file;
    private StateData s = new StateData();
    private final Set<String> eventIds = new HashSet<>();
    private boolean dirty;
    private final List<MetricRow> metrics = new ArrayList<>();
    private final Set<String> metricIds = new LinkedHashSet<>();

    public StateStore(Path file) {
        this.file = file;
    }

    // ---------- locking, persistence, replication ----------

    public synchronized <T> T read(Function<StateData, T> f) {
        return f.apply(s);
    }

    public synchronized <T> T write(Function<StateData, T> f) {
        T out = f.apply(s);
        s.version++;
        dirty = true;
        return out;
    }

    public synchronized long version() {
        return s.version;
    }

    public synchronized long epoch() {
        return s.epoch;
    }

    public synchronized String toJson() {
        return Json.write(s);
    }

    // Replaces the whole state with a copy received from the leader. Returns events that are new to us.
    public synchronized List<Map<String, Object>> replace(String json) {
        StateData next = Json.read(json, StateData.class);
        List<Map<String, Object>> fresh = new ArrayList<>();
        for (Map<String, Object> e : next.events) if (!eventIds.contains(String.valueOf(e.get("eventId")))) fresh.add(e);
        s = next;
        rebuildEventIds();
        dirty = true;
        return fresh;
    }

    public synchronized void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            s = Json.read(Files.readString(file, StandardCharsets.UTF_8), StateData.class);
            rebuildEventIds();
            Log.info("state_loaded", "file", file.toString(), "version", s.version);
        } catch (IOException | RuntimeException e) {
            Log.error("state_load_failed", e, "file", file.toString());
        }
    }

    private void rebuildEventIds() {
        eventIds.clear();
        for (Map<String, Object> e : s.events) eventIds.add(String.valueOf(e.get("eventId")));
    }

    // Writes to a temp file then renames it, so a crash never leaves a half-written state file.
    public void persistIfDirty() {
        String json;
        synchronized (this) {
            if (!dirty || file == null) return;
            json = Json.write(s);
            dirty = false;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.error("state_save_failed", e, "file", file.toString());
            synchronized (this) {
                dirty = true;
            }
        }
    }

    // ---------- seed ----------

    public void seed() {
        write(st -> {
            long now = System.currentTimeMillis();
            if (st.environments.isEmpty()) {
                addEnv(st, "production", "Production", now);
                addEnv(st, "staging", "Staging", now);
            }
            if (st.flags.isEmpty()) {
                createFlag(st, "new-checkout", "New checkout flow", "Single-page checkout replacing the three-step flow.", "boolean", "true", "false");
                createFlag(st, "search-ranker", "Search ranking model", "Selects the ranking model used by product search.", "string", "\"semantic-v2\"", "\"bm25\"");
                createFlag(st, "recs-limit", "Recommendation count", "Number of recommendations rendered on the product page.", "number", "12", "6");
                createFlag(st, "promo-banner", "Promotional banner", "Banner configuration for the home page.", "json",
                        "{\"text\":\"Free shipping this week\",\"tone\":\"green\"}", "{\"text\":\"\",\"tone\":\"none\"}");
            }
            if (st.latestBundle == null) newRevision(st, "initial configuration", "system", null, null, null, null, null);
            return null;
        });
    }

    private static void addEnv(StateData st, String key, String name, long now) {
        StateData.Env e = new StateData.Env();
        e.key = key;
        e.name = name;
        e.createdAt = now;
        st.environments.put(key, e);
    }

    // ---------- environments and flags ----------

    public static void createEnvironment(StateData st, String key, String name) {
        if (st.environments.containsKey(key)) throw new ApiServer.ApiError(409, "Environment '" + key + "' already exists");
        long now = System.currentTimeMillis();
        addEnv(st, key, name, now);
        for (FlagRec f : st.flags.values()) {
            String off = defaultOff(f.type);
            putEnv(f, key, new FlagEnvConfig(false, List.of(), 0, off, off), now);
        }
    }

    static String defaultOff(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "string" -> "\"\"";
            case "number" -> "0";
            default -> "{}";
        };
    }

    private static void putEnv(FlagRec f, String env, FlagEnvConfig cfg, long now) {
        EnvCfg ec = new EnvCfg();
        ec.config = cfg;
        ec.updatedAt = now;
        f.envs.put(env, ec);
    }

    public static void createFlag(StateData st, String key, String name, String desc, String type, String on, String off) {
        if (st.flags.containsKey(key)) throw new ApiServer.ApiError(409, "Flag '" + key + "' already exists");
        long now = System.currentTimeMillis();
        FlagRec f = new FlagRec();
        f.key = key;
        f.name = name;
        f.description = desc == null ? "" : desc;
        f.type = type;
        f.createdAt = now;
        f.updatedAt = now;
        for (String env : st.environments.keySet()) putEnv(f, env, new FlagEnvConfig(false, List.of(), 0, on, off), now);
        st.flags.put(key, f);
    }

    public static FlagRec flag(StateData st, String key) {
        FlagRec f = st.flags.get(key);
        if (f == null) throw new ApiServer.ApiError(404, "Flag '" + key + "' not found");
        return f;
    }

    public static FlagEnvConfig envConfig(StateData st, String flag, String env) {
        EnvCfg ec = flag(st, flag).envs.get(env);
        if (ec == null) throw new ApiServer.ApiError(404, "No configuration for flag '" + flag + "' in environment '" + env + "'");
        return ec.config;
    }

    public static String resource(String flagKey, String env) {
        return "flag:" + flagKey + ":" + env;
    }

    // ---------- revisions ----------

    // Every change creates the next global revision. Revisions form one total order, so a bundle
    // always contains every earlier change and agents can simply ignore older revisions.
    public static ConfigBundle newRevision(StateData st, String reason, String actor, String flagKey, String env,
                                           Long fenceToken, FlagEnvConfig before, FlagEnvConfig after) {
        long now = System.currentTimeMillis();
        RevisionRec r = new RevisionRec();
        r.revision = st.nextRevision++;
        r.createdAt = now;
        r.reason = reason;
        r.actor = actor;
        r.flagKey = flagKey;
        r.envKey = env;
        r.fenceToken = fenceToken;
        r.beforeConfig = before;
        r.afterConfig = after;
        st.revisions.add(r);
        if (st.revisions.size() > MAX_REVISIONS) st.revisions.remove(0);
        Map<String, FlagSnapshot> flags = new TreeMap<>();
        for (FlagRec f : st.flags.values()) {
            Map<String, FlagEnvConfig> envs = new TreeMap<>();
            f.envs.forEach((k, v) -> envs.put(k, v.config));
            flags.put(f.key, new FlagSnapshot(f.key, f.type, envs));
        }
        st.latestBundle = new ConfigBundle(r.revision, now, reason, flags);
        return st.latestBundle;
    }

    // Writes one flag/env config as a new revision; the fencing check rejects stale lease holders.
    public static ConfigBundle writeConfig(StateData st, String flagKey, String env, FlagEnvConfig cfg, String reason,
                                           String actor, long fenceToken) {
        checkFence(st, resource(flagKey, env), fenceToken);
        FlagRec f = flag(st, flagKey);
        FlagEnvConfig before = envConfig(st, flagKey, env);
        long now = System.currentTimeMillis();
        putEnv(f, env, cfg, now);
        f.updatedAt = now;
        return newRevision(st, reason, actor, flagKey, env, fenceToken, before, cfg);
    }

    public static long latestRevision(StateData st) {
        return st.latestBundle == null ? 0 : st.latestBundle.revision();
    }

    // ---------- fencing ----------

    public static long nextToken(StateData st) {
        return ++st.fenceCounter;
    }

    // Accepts the token only if no newer token was accepted for this resource before.
    public static void checkFence(StateData st, String resource, long token) {
        long last = st.fenceGuard.getOrDefault(resource, 0L);
        if (token < last) throw new FencingRejected(resource, token, last);
        st.fenceGuard.put(resource, token);
    }

    // ---------- agents ----------

    public static void register(StateData st, Rpc.RegisterRequest r, String controllerId, long suspectAfterMs) {
        long now = System.currentTimeMillis();
        AgentRec a = st.agents.get(r.agentId());
        if (a == null) {
            a = new AgentRec();
            a.id = r.agentId();
            a.registeredAt = now;
            st.agents.put(a.id, a);
        } else if (a.incarnation != r.incarnation() || now - a.lastHeartbeat > suspectAfterMs) {
            a.recoveredAt = now;
        }
        a.envKey = r.env();
        a.address = r.address();
        a.incarnation = r.incarnation();
        a.appliedRevision = r.appliedRevision();
        a.lastHeartbeat = now;
        a.lastController = controllerId;
        a.staleSince = null;
    }

    // Returns false for an unknown agent or an old incarnation, which tells the agent to register again.
    public static boolean heartbeat(StateData st, Rpc.HeartbeatRequest h, String controllerId, long suspectAfterMs) {
        AgentRec a = st.agents.get(h.agentId());
        if (a == null || a.incarnation != h.incarnation()) return false;
        long now = System.currentTimeMillis();
        if (now - a.lastHeartbeat > suspectAfterMs) a.recoveredAt = now;
        a.lastHeartbeat = now;
        a.heartbeats++;
        a.appliedRevision = Math.max(a.appliedRevision, h.appliedRevision());
        long desired = latestRevision(st);
        if (a.appliedRevision < desired) {
            if (a.staleSince == null) a.staleSince = now;
        } else {
            a.staleSince = null;
        }
        if (h.clockOffsetMs() != null) a.clockOffsetMs = h.clockOffsetMs();
        if (h.rttMs() != null) a.rttMs = h.rttMs();
        a.activeFaults = h.activeFaults() == null ? List.of() : List.copyOf(h.activeFaults());
        a.lastController = controllerId;
        return true;
    }

    // Idempotent: only moves the applied revision forward, so duplicate or late acks change nothing.
    public static boolean ack(StateData st, String agentId, long revision) {
        AgentRec a = st.agents.get(agentId);
        if (a == null || a.appliedRevision >= revision) return false;
        long now = System.currentTimeMillis();
        a.appliedRevision = revision;
        a.appliedAt = now;
        for (RevisionRec r : st.revisions) if (r.revision == revision) a.lastAckLagMs = now - r.createdAt;
        if (revision >= latestRevision(st)) a.staleSince = null;
        return true;
    }

    public List<Map<String, Object>> agents(FailureDetector.Settings fd, boolean includeRetired) {
        return read(st -> agentViews(st, fd, includeRetired));
    }

    static List<Map<String, Object>> agentViews(StateData st, FailureDetector.Settings fd, boolean includeRetired) {
        long now = System.currentTimeMillis();
        long desired = latestRevision(st);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentRec a : st.agents.values()) {
            long age = now - a.lastHeartbeat;
            // Agents silent for a minute are treated as retired (for example a replica removed by scale-down).
            boolean retired = age > 60_000;
            if (retired && !includeRetired) continue;
            FailureDetector.Health h = FailureDetector.classify(fd, age,
                    a.staleSince == null ? 0 : now - a.staleSince, a.recoveredAt == null ? -1 : now - a.recoveredAt);
            Map<String, Object> m = view(a);
            m.put("ageMs", age);
            m.put("health", h.name());
            m.put("live", FailureDetector.isLive(h));
            m.put("retired", retired);
            m.put("desiredRevision", desired);
            m.put("revisionLag", desired - a.appliedRevision);
            out.add(m);
        }
        // Live agents first, then by id, so the dashboard shows the working fleet at the top.
        out.sort(java.util.Comparator.comparing((Map<String, Object> m) -> !Boolean.TRUE.equals(m.get("live")))
                .thenComparing(m -> String.valueOf(m.get("id"))));
        return out;
    }

    // ---------- events ----------

    // Returns true when the event is new; duplicates (retries, forwarded copies) are ignored.
    public boolean addEvent(Msg m) {
        Map<String, Object> b = m.body() == null ? Map.of() : m.body();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", m.eventId());
        e.put("nodeId", m.node());
        e.put("type", m.type());
        e.put("lamport", m.lamport());
        e.put("vclock", m.vclock() == null ? Map.of() : m.vclock());
        e.put("wallTs", iso(m.wallTs()));
        e.put("recordedAt", iso(System.currentTimeMillis()));
        e.put("revision", b.get("revision") instanceof Number n ? n.longValue() : null);
        e.put("outcome", String.valueOf(b.getOrDefault("outcome", "ok")));
        e.put("simulated", Boolean.TRUE.equals(b.get("simulated")));
        e.put("data", b);
        synchronized (this) {
            if (!eventIds.add(m.eventId())) return false;
            s.events.add(e);
            if (s.events.size() > MAX_EVENTS) eventIds.remove(String.valueOf(s.events.remove(0).get("eventId")));
            s.version++;
            dirty = true;
            return true;
        }
    }

    public synchronized List<Map<String, Object>> events(int limit, String typePrefix, String node) {
        int lim = Math.max(1, Math.min(limit, 1000));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = s.events.size() - 1; i >= 0 && out.size() < lim; i--) {
            Map<String, Object> e = s.events.get(i);
            if (typePrefix != null && !typePrefix.isBlank() && !String.valueOf(e.get("type")).startsWith(typePrefix)) continue;
            if (node != null && !node.isBlank() && !node.equals(e.get("nodeId"))) continue;
            out.add(e);
        }
        return out;
    }

    public synchronized Map<String, Object> event(String id) {
        for (Map<String, Object> e : s.events) if (id.equals(e.get("eventId"))) return e;
        return null;
    }

    // ---------- metrics (memory only) ----------

    public synchronized void addMetric(String id, String agent, String flag, String env, long revision, String variant,
                                       long requests, long errors, long[] hist) {
        if (!metricIds.add(id)) return;
        long now = System.currentTimeMillis();
        metrics.add(new MetricRow(id, now, agent, flag, env, revision, variant, requests, errors, hist));
        // Keep two hours of data.
        while (!metrics.isEmpty() && now - metrics.get(0).at() > 7_200_000) metricIds.remove(metrics.remove(0).id());
        if (metricIds.size() > 200_000) metricIds.remove(metricIds.iterator().next());
    }

    public synchronized WindowStats stats(String flag, String env, String variant, long minRevision, long sinceMs) {
        long req = 0;
        long err = 0;
        Histogram h = new Histogram();
        for (MetricRow m : metrics) {
            if (m.at() < sinceMs || m.revision() < minRevision || !m.flag().equals(flag) || !m.env().equals(env) || !m.variant().equals(variant)) continue;
            req += m.requests();
            err += m.errors();
            h.merge(m.hist());
        }
        return new WindowStats(req, err, h.quantileUpperBoundMs(0.95));
    }

    public synchronized Map<String, Long> totals(String flag, long sinceMs) {
        long req = 0;
        long err = 0;
        for (MetricRow m : metrics) {
            if (m.at() >= sinceMs && m.flag().equals(flag)) {
                req += m.requests();
                err += m.errors();
            }
        }
        return Map.of("requests", req, "errors", err);
    }

    // Traffic time series in 10-second buckets for charts.
    public synchronized List<Map<String, Object>> series(String flag, String env, int minutes) {
        long since = System.currentTimeMillis() - Math.max(1, Math.min(minutes, 120)) * 60_000L;
        Map<String, Object[]> buckets = new TreeMap<>();
        for (MetricRow m : metrics) {
            if (m.at() < since || !m.flag().equals(flag) || !("*".equals(env) || m.env().equals(env))) continue;
            long t = m.at() / 10_000 * 10_000;
            Object[] b = buckets.computeIfAbsent(String.format("%015d|%s", t, m.variant()), k -> new Object[]{t, m.variant(), 0L, 0L, new Histogram()});
            b[2] = (Long) b[2] + m.requests();
            b[3] = (Long) b[3] + m.errors();
            ((Histogram) b[4]).merge(m.hist());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] b : buckets.values()) {
            Map<String, Object> r = new LinkedHashMap<>();
            long req = (Long) b[2];
            r.put("t", iso((Long) b[0]));
            r.put("variant", b[1]);
            r.put("requests", req);
            r.put("errors", b[3]);
            r.put("p95Ms", ((Histogram) b[4]).quantileUpperBoundMs(0.95));
            r.put("errorRate", req == 0 ? 0 : ((Long) b[3]).doubleValue() / req);
            out.add(r);
        }
        return out;
    }

    // ---------- views (JSON shapes used by the dashboard) ----------

    public static String iso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }

    // Converts a record to a map and turns every *At field (epoch ms) into an ISO timestamp.
    @SuppressWarnings("unchecked")
    public static Map<String, Object> view(Object rec) {
        Map<String, Object> m = Json.map(rec);
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getKey().endsWith("At") && e.getValue() instanceof Number n) e.setValue(iso(n.longValue()));
            else if (e.getKey().equals("lastHeartbeat") && e.getValue() instanceof Number n) e.setValue(iso(n.longValue()));
            else if (e.getValue() instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
                List<Object> conv = new ArrayList<>();
                for (Object o : l) conv.add(view(o));
                e.setValue(conv);
            }
        }
        if (m.containsKey("at") && m.get("at") instanceof Number n) m.put("at", iso(n.longValue()));
        return m;
    }

    public static Map<String, Object> flagView(StateData st, FlagRec f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", f.key);
        m.put("name", f.name);
        m.put("description", f.description);
        m.put("type", f.type);
        m.put("createdAt", iso(f.createdAt));
        m.put("updatedAt", iso(f.updatedAt));
        Map<String, Object> envs = new TreeMap<>();
        f.envs.forEach((k, v) -> {
            Map<String, Object> c = Json.map(v.config);
            c.put("updatedAt", iso(v.updatedAt));
            envs.put(k, c);
        });
        m.put("envs", envs);
        List<Map<String, Object>> active = new ArrayList<>();
        for (RolloutRec r : st.rollouts.values()) {
            if (r.flagKey.equals(f.key) && ("RUNNING".equals(r.state) || "PAUSED".equals(r.state))) {
                active.add(Map.of("id", r.id, "envKey", r.envKey, "state", r.state, "currentStage", r.currentStage, "stages", r.stages));
            }
        }
        if (!active.isEmpty()) m.put("activeRollouts", active);
        return m;
    }

    public static List<Map<String, Object>> flagViews(StateData st) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FlagRec f : st.flags.values()) out.add(flagView(st, f));
        return out;
    }

    public static List<Map<String, Object>> revisionViews(StateData st, String flagKey, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = st.revisions.size() - 1; i >= 0 && out.size() < limit; i--) {
            RevisionRec r = st.revisions.get(i);
            if (flagKey == null || flagKey.equals(r.flagKey)) out.add(view(r));
        }
        return out;
    }

    public static List<Map<String, Object>> viewAll(Collection<?> recs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : recs) out.add(view(r));
        return out;
    }
}
