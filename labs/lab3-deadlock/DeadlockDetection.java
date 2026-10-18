import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// Lab 3: deadlock detection.
// Part A builds a wait-for graph from a resource-allocation table, finds cycles with DFS and
//        resolves them by aborting the youngest process (centralized detection).
// Part B runs the Chandy-Misra-Haas edge-chasing algorithm: each site is a thread and probes
//        travel as messages between sites (distributed detection).
// Run: javac DeadlockDetection.java && java DeadlockDetection
public class DeadlockDetection {

    public static void main(String[] args) throws Exception {
        System.out.println("========== Part A: centralized wait-for graph ==========");
        partA();
        System.out.println();
        System.out.println("========== Part B: Chandy-Misra-Haas edge chasing ==========");
        partB();
    }

    // ---------------- Part A ----------------

    // Scenario taken from DeployGuard: release workflows that must lock several feature flags.
    static void partA() {
        // resource -> process holding it
        Map<String, String> holder = new LinkedHashMap<>();
        holder.put("flag:checkout", "W1");
        holder.put("flag:payments", "W2");
        holder.put("flag:search", "W3");
        holder.put("flag:banner", "W4");
        // process -> resource it is waiting for
        Map<String, String> waitsFor = new LinkedHashMap<>();
        waitsFor.put("W1", "flag:payments");
        waitsFor.put("W2", "flag:search");
        waitsFor.put("W3", "flag:checkout");
        waitsFor.put("W4", "flag:search");
        // Start times decide the victim: the youngest (latest start) loses the least work.
        Map<String, Integer> startTime = Map.of("W1", 10, "W2", 20, "W3", 30, "W4", 40);

        System.out.println("Holds:     " + holder);
        System.out.println("Waits for: " + waitsFor);

        while (true) {
            Map<String, Set<String>> wfg = buildWaitForGraph(holder, waitsFor);
            System.out.println("Wait-for graph: " + wfg);
            List<String> cycle = findCycle(wfg);
            if (cycle.isEmpty()) {
                System.out.println("No cycle: the system is deadlock-free.");
                break;
            }
            String victim = Collections.max(cycle, (a, b) -> startTime.get(a) - startTime.get(b));
            System.out.println("DEADLOCK cycle " + cycle + " -> abort youngest " + victim
                    + " (start time " + startTime.get(victim) + ")");
            // Aborting the victim releases what it holds and cancels its own request.
            holder.values().removeIf(victim::equals);
            waitsFor.remove(victim);
            // Waiters on released resources now get them.
            for (Map.Entry<String, String> e : new ArrayList<>(waitsFor.entrySet())) {
                if (!holder.containsKey(e.getValue())) {
                    holder.put(e.getValue(), e.getKey());
                    waitsFor.remove(e.getKey());
                    System.out.println("  " + e.getKey() + " now acquires " + e.getValue());
                }
            }
        }
    }

    // Edge P -> Q when P waits for a resource held by Q.
    static Map<String, Set<String>> buildWaitForGraph(Map<String, String> holder, Map<String, String> waitsFor) {
        Map<String, Set<String>> g = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : waitsFor.entrySet()) {
            String h = holder.get(e.getValue());
            if (h != null && !h.equals(e.getKey())) g.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(h);
        }
        return g;
    }

    // DFS with three colours: white (unvisited), grey (on the current path), black (done).
    // Reaching a grey node again means we found a cycle. O(V + E).
    static List<String> findCycle(Map<String, Set<String>> g) {
        Map<String, Integer> colour = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        for (String start : g.keySet()) {
            List<String> c = dfs(start, g, colour, parent);
            if (!c.isEmpty()) return c;
        }
        return List.of();
    }

    private static List<String> dfs(String u, Map<String, Set<String>> g, Map<String, Integer> colour, Map<String, String> parent) {
        if (colour.getOrDefault(u, 0) != 0) return List.of();
        colour.put(u, 1);
        for (String v : g.getOrDefault(u, Set.of())) {
            if (colour.getOrDefault(v, 0) == 1) {
                // Back edge u -> v: walk parents from u back to v to list the cycle.
                List<String> cycle = new ArrayList<>();
                for (String x = u; !x.equals(v); x = parent.get(x)) cycle.add(x);
                cycle.add(v);
                Collections.reverse(cycle);
                return cycle;
            }
            if (colour.getOrDefault(v, 0) == 0) {
                parent.put(v, u);
                List<String> c = dfs(v, g, colour, parent);
                if (!c.isEmpty()) return c;
            }
        }
        colour.put(u, 2);
        return List.of();
    }

    // ---------------- Part B ----------------

    // probe(i, j, k): initiated by process i, sent by j (on site of j) to k.
    static final class Probe {
        final String initiator, from, to;

        Probe(String initiator, String from, String to) {
            this.initiator = initiator;
            this.from = from;
            this.to = to;
        }

        String initiator() { return initiator; }
        String from() { return from; }
        String to() { return to; }
    }

    static final Map<String, String> siteOf = new HashMap<>();
    static final Map<String, BlockingQueue<Probe>> inbox = new HashMap<>();
    static final Map<String, Set<String>> dependsOn = new ConcurrentHashMap<>();
    static final Set<String> deadlocked = ConcurrentHashMap.newKeySet();
    static int messages = 0;

    static void partB() throws InterruptedException {
        // Five processes spread over three sites. P1 -> P2 -> P3 -> P4 -> P1 is a cycle across sites;
        // P5 waits for P1 but is not part of the cycle.
        siteOf.put("P1", "S1");
        siteOf.put("P2", "S1");
        siteOf.put("P3", "S2");
        siteOf.put("P4", "S3");
        siteOf.put("P5", "S3");
        dependsOn.put("P1", Set.of("P2"));
        dependsOn.put("P2", Set.of("P3"));
        dependsOn.put("P3", Set.of("P4"));
        dependsOn.put("P4", Set.of("P1"));
        dependsOn.put("P5", Set.of("P1"));
        System.out.println("Sites: " + siteOf);
        System.out.println("Dependencies (blocked process -> process it waits for): " + dependsOn);

        List<Thread> sites = new ArrayList<>();
        for (String s : List.of("S1", "S2", "S3")) {
            inbox.put(s, new LinkedBlockingQueue<>());
            Thread t = new Thread(() -> runSite(s), s);
            sites.add(t);
            t.start();
        }
        // P5 and P1 are blocked and each initiates detection.
        initiate("P5");
        initiate("P1");
        Thread.sleep(1500);
        sites.forEach(Thread::interrupt);
        for (Thread t : sites) t.join();
        System.out.println("Processes that detected they are deadlocked: " + deadlocked);
        System.out.println("Probe messages sent: " + messages);
        System.out.println("P5 is blocked but not deadlocked: its probe never comes back to it.");
    }

    static synchronized void send(Probe p) {
        messages++;
        System.out.println("  " + siteOf.get(p.from()) + " -> " + siteOf.get(p.to()) + " : probe(" + p.initiator() + ", " + p.from() + ", " + p.to() + ")");
        inbox.get(siteOf.get(p.to())).add(p);
    }

    static void initiate(String i) {
        System.out.println(i + " is blocked and starts detection");
        for (String k : dependsOn.getOrDefault(i, Set.of())) send(new Probe(i, i, k));
    }

    // Each site forwards a probe once per (initiator, process) pair, so probes cannot loop forever.
    static void runSite(String site) {
        Set<String> forwarded = new LinkedHashSet<>();
        try {
            while (true) {
                Probe p = inbox.get(site).poll(100, TimeUnit.MILLISECONDS);
                if (p == null) continue;
                String k = p.to();
                if (k.equals(p.initiator())) {
                    deadlocked.add(k);
                    System.out.println("  *** " + k + " received its own probe: DEADLOCK detected at " + site);
                    continue;
                }
                // Only a blocked process (one with dependencies) passes the probe on.
                if (dependsOn.containsKey(k) && forwarded.add(p.initiator() + "@" + k)) {
                    for (String m : dependsOn.get(k)) send(new Probe(p.initiator(), k, m));
                }
            }
        } catch (InterruptedException e) {
            // Simulation finished.
        }
    }
}
