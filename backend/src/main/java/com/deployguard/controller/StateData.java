package com.deployguard.controller;

import com.deployguard.core.ConfigBundle;
import com.deployguard.core.ConfigBundle.FlagEnvConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Everything the control plane keeps. The leader owns it, saves it as JSON and copies it to the followers.
// Times are epoch milliseconds.
public final class StateData {
    public long version;          // bumped on every change
    public long epoch;            // election epoch of the leader that wrote it
    public long nextRevision = 1;
    public long nextRolloutId = 1;
    public long nextRunId = 1;
    public long fenceCounter;     // source of fencing tokens; never goes backwards
    public Map<String, Long> fenceGuard = new HashMap<>(); // resource -> last accepted token

    public Map<String, Env> environments = new TreeMap<>();
    public Map<String, FlagRec> flags = new TreeMap<>();
    public List<RevisionRec> revisions = new ArrayList<>();
    public ConfigBundle latestBundle;
    public Map<Long, RolloutRec> rollouts = new TreeMap<>();
    public Map<String, AgentRec> agents = new TreeMap<>();
    public Map<String, Object> traffic = new LinkedHashMap<>(Map.of("enabled", true, "rps", 40));
    public List<Map<String, Object>> events = new ArrayList<>();
    public Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
    public Map<Long, RunRec> runs = new TreeMap<>();

    public static final class Env {
        public String key;
        public String name;
        public long createdAt;
    }

    public static final class EnvCfg {
        public FlagEnvConfig config;
        public long updatedAt;
    }

    public static final class FlagRec {
        public String key;
        public String name;
        public String description = "";
        public String type;
        public long createdAt;
        public long updatedAt;
        public Map<String, EnvCfg> envs = new TreeMap<>();
    }

    public static final class RevisionRec {
        public long revision;
        public long createdAt;
        public String reason;
        public String actor;
        public String flagKey;
        public String envKey;
        public Long fenceToken;
        public FlagEnvConfig beforeConfig;
        public FlagEnvConfig afterConfig;
    }

    public static final class HistoryRec {
        public long id;
        public String fromState;
        public String toState;
        public int stage;
        public int percent;
        public Long revision;
        public String reason;
        public String actor;
        public long fenceToken;
        public long lamport;
        public long at;
    }

    public static final class RolloutRec {
        public long id;
        public String requestId;
        public String flagKey;
        public String envKey;
        public String state;
        public List<Integer> stages;
        public int currentStage;
        public long stageStartedAt;
        public long stageRevision;
        public int observeSeconds;
        public double maxErrorRate;
        public long maxP95Ms;
        public long minRequests;
        public String onBreach;
        public boolean autoPromote;
        public FlagEnvConfig baselineConfig;
        public FlagEnvConfig targetConfig;
        public String lastVerdict;
        public String lastDetail;
        public String rollbackReason;
        public Long breachDetectedAt;
        public Long rollbackRevision;
        public String createdBy;
        public long createdAt;
        public long updatedAt;
        public Long finishedAt;
        public List<HistoryRec> history = new ArrayList<>();
    }

    public static final class AgentRec {
        public String id;
        public String envKey;
        public String address;
        public long incarnation;
        public long registeredAt;
        public long lastHeartbeat;
        public long appliedRevision;
        public Long appliedAt;
        public Long lastAckLagMs;
        public Long staleSince;
        public Long recoveredAt;
        public Long clockOffsetMs;
        public Long rttMs;
        public List<String> activeFaults = new ArrayList<>();
        public String lastController;
        public long heartbeats;
    }

    public static final class RunRec {
        public long id;
        public String scenario;
        public Map<String, Object> params = new LinkedHashMap<>();
        public String status;
        public long startedAt;
        public Long finishedAt;
        public Map<String, Object> result = new LinkedHashMap<>();
        public String initiatedBy;
    }
}
