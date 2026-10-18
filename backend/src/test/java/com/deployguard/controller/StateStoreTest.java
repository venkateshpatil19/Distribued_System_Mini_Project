package com.deployguard.controller;

import com.deployguard.common.Msg;
import com.deployguard.common.NodeClock;
import com.deployguard.core.ConfigBundle;
import com.deployguard.core.ConfigBundle.FlagEnvConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateStoreTest {

    @Test
    void seedCreatesDemoDataOnlyOnce() {
        StateStore s = new StateStore(null);
        s.seed();
        long rev = s.read(StateStore::latestRevision);
        s.seed();
        assertEquals(rev, s.read(StateStore::latestRevision));
        assertEquals(4, (int) s.read(st -> st.flags.size()));
        assertEquals(2, (int) s.read(st -> st.environments.size()));
    }

    @Test
    void staleFencingTokenIsRejected() {
        StateStore s = new StateStore(null);
        s.seed();
        long t1 = s.write(StateStore::nextToken);
        long t2 = s.write(StateStore::nextToken);
        FlagEnvConfig cfg = new FlagEnvConfig(true, List.of(), 50, "true", "false");
        ConfigBundle b = s.write(st -> StateStore.writeConfig(st, "new-checkout", "production", cfg, "test", "t", t2));
        assertTrue(b.revision() > 1);
        assertThrows(StateStore.FencingRejected.class,
                () -> s.write(st -> StateStore.writeConfig(st, "new-checkout", "production", cfg, "late", "t", t1)));
        assertEquals(b.revision(), s.read(StateStore::latestRevision));
    }

    @Test
    void stateSurvivesSaveAndReload(@TempDir Path dir) {
        Path file = dir.resolve("controller-1.json");
        StateStore a = new StateStore(file);
        a.seed();
        a.persistIfDirty();
        StateStore b = new StateStore(file);
        b.load();
        assertEquals(a.version(), b.version());
        assertEquals(a.read(StateStore::latestRevision), b.read(StateStore::latestRevision));
    }

    @Test
    void duplicateEventsAreIgnoredAndReplicaReportsOnlyNewOnes() {
        StateStore leader = new StateStore(null);
        StateStore follower = new StateStore(null);
        Msg m = Msg.of("TEST", new NodeClock("n1").tick(), Map.of("outcome", "ok"));
        assertTrue(leader.addEvent(m));
        assertFalse(leader.addEvent(m));
        assertEquals(1, follower.replace(leader.toJson()).size());
        assertEquals(0, follower.replace(leader.toJson()).size());
    }
}
