package com.deployguard.core;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitForGraphTest {

    @Test
    void acyclicGraphHasNoCycle() {
        WaitForGraph g = new WaitForGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");
        g.addEdge("A", "C");
        assertTrue(g.findCycle().isEmpty());
    }

    @Test
    void findsTwoAndThreeNodeCycles() {
        WaitForGraph g = new WaitForGraph();
        g.addEdge("W1", "W2");
        g.addEdge("W2", "W1");
        assertEquals(2, g.findCycle().size());

        WaitForGraph h = new WaitForGraph();
        h.addEdge("X", "W1");
        h.addEdge("W1", "W2");
        h.addEdge("W2", "W3");
        h.addEdge("W3", "W1");
        List<String> c = h.findCycle();
        assertEquals(List.of("W1", "W2", "W3"), c);
    }

    @Test
    void selfLoopsAreIgnored() {
        WaitForGraph g = new WaitForGraph();
        g.addEdge("A", "A");
        assertTrue(g.findCycle().isEmpty());
    }

    @Test
    void resolveAbortsVictimsUntilAcyclic() {
        WaitForGraph g = new WaitForGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "A");
        g.addEdge("C", "D");
        g.addEdge("D", "C");
        Map<String, Integer> age = Map.of("A", 1, "B", 2, "C", 3, "D", 4);
        // Abort the youngest member of each cycle.
        List<String> victims = g.resolve(cycle -> Collections.max(cycle, (x, y) -> age.get(x) - age.get(y)));
        assertEquals(List.of("B", "D"), victims);
        assertTrue(g.findCycle().isEmpty());
    }

    @Test
    void longChainDoesNotOverflow() {
        WaitForGraph g = new WaitForGraph();
        for (int i = 0; i < 50_000; i++) g.addEdge("n" + i, "n" + (i + 1));
        assertTrue(g.findCycle().isEmpty());
        g.addEdge("n50000", "n0");
        assertEquals(50_001, g.findCycle().size());
    }
}
