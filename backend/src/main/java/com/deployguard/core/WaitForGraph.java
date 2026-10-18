package com.deployguard.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

// Wait-for graph: an edge a -> b means process a waits for a resource held by b.
// A cycle means deadlock under the single-resource-request model.
public final class WaitForGraph {
    private final Map<String, Set<String>> edges = new LinkedHashMap<>();

    public void addEdge(String waiter, String holder) {
        if (waiter.equals(holder)) return;
        edges.computeIfAbsent(waiter, k -> new LinkedHashSet<>()).add(holder);
        edges.computeIfAbsent(holder, k -> new LinkedHashSet<>());
    }

    public void removeNode(String node) {
        edges.remove(node);
        for (Set<String> out : edges.values()) out.remove(node);
    }

    public Map<String, Set<String>> edges() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        edges.forEach((k, v) -> copy.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return copy;
    }

    private static final class Frame {
        final String node;
        final List<String> children;
        int next;

        Frame(String node, List<String> children) {
            this.node = node;
            this.children = children;
        }
    }

    // Returns one cycle as an ordered node list, or an empty list.
    // Iterative three-colour DFS, O(V + E), so long wait chains cannot overflow the stack.
    public List<String> findCycle() {
        Map<String, Integer> colour = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        for (String start : edges.keySet()) {
            if (colour.getOrDefault(start, 0) != 0) continue;
            ArrayDeque<Frame> stack = new ArrayDeque<>();
            stack.push(new Frame(start, new ArrayList<>(edges.get(start))));
            colour.put(start, 1);
            while (!stack.isEmpty()) {
                Frame f = stack.peek();
                if (f.next < f.children.size()) {
                    String child = f.children.get(f.next++);
                    int c = colour.getOrDefault(child, 0);
                    if (c == 0) {
                        parent.put(child, f.node);
                        colour.put(child, 1);
                        stack.push(new Frame(child, new ArrayList<>(edges.getOrDefault(child, Set.of()))));
                    } else if (c == 1) {
                        // Back edge closes a cycle; walk parents from f.node back to child.
                        List<String> cycle = new ArrayList<>();
                        String cur = f.node;
                        cycle.add(cur);
                        while (!cur.equals(child)) {
                            cur = parent.get(cur);
                            cycle.add(cur);
                        }
                        Collections.reverse(cycle);
                        return cycle;
                    }
                } else {
                    colour.put(f.node, 2);
                    stack.pop();
                }
            }
        }
        return List.of();
    }

    // Repeatedly finds a cycle and removes the chosen victim until the graph is acyclic.
    public List<String> resolve(Function<List<String>, String> chooseVictim) {
        List<String> victims = new ArrayList<>();
        Set<String> removed = new HashSet<>();
        List<String> cycle;
        while (!(cycle = findCycle()).isEmpty()) {
            String victim = chooseVictim.apply(cycle);
            if (victim == null || !cycle.contains(victim) || !removed.add(victim)) break;
            victims.add(victim);
            removeNode(victim);
        }
        return victims;
    }
}
