import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// Lab 4 (part 2): Berkeley clock synchronization.
// A master polls every slave's clock, corrects each reading for half the round-trip time,
// ignores readings that are too far off (faulty clocks), averages the rest and sends each node
// the adjustment it needs. Clocks are simulated so the drift and the network delay are visible.
// Run: javac Berkeley.java && java Berkeley
public class Berkeley {

    static final class Node {
        final String name;
        long offsetMs; // how far this node's clock is from "true" time

        Node(String name, long offsetMs) {
            this.name = name;
            this.offsetMs = offsetMs;
        }

        long read(long trueTime) {
            return trueTime + offsetMs;
        }
    }

    public static void main(String[] args) {
        Random rnd = new Random(7);
        Node master = new Node("master", 0);
        List<Node> nodes = new ArrayList<>();
        nodes.add(master);
        nodes.add(new Node("agent-1", +180));
        nodes.add(new Node("agent-2", -120));
        nodes.add(new Node("agent-3", +60));
        nodes.add(new Node("agent-4", +5000)); // faulty clock, should be ignored
        long faultLimitMs = 1000;

        long trueTime = 1_000_000;
        System.out.println("Before sync:");
        nodes.forEach(n -> System.out.printf("  %-8s offset %+5d ms%n", n.name, n.offsetMs));

        // Step 1: poll. Each reply takes a random round-trip time; the master estimates the
        // slave's clock at "now" as reading + RTT/2 (the same idea as Cristian's algorithm).
        List<Long> diffs = new ArrayList<>();
        List<Node> used = new ArrayList<>();
        for (Node n : nodes) {
            long rtt = n == master ? 0 : 10 + rnd.nextInt(30);
            long reading = n.read(trueTime + rtt / 2); // slave reads its clock halfway through the round trip
            long estimatedNow = reading + rtt / 2;
            long masterNow = master.read(trueTime + rtt);
            long diff = estimatedNow - masterNow;
            System.out.printf("  poll %-8s rtt %2d ms -> differs from master by %+5d ms%s%n", n.name, rtt, diff,
                    Math.abs(diff) > faultLimitMs ? "  (ignored: beyond " + faultLimitMs + " ms)" : "");
            if (Math.abs(diff) <= faultLimitMs) {
                diffs.add(diff);
                used.add(n);
            }
        }

        // Step 2: fault-tolerant average of the differences (master included with 0).
        long avg = Math.round(diffs.stream().mapToLong(Long::longValue).average().orElse(0));
        System.out.println("Average difference of " + used.size() + " good clocks: " + avg + " ms");

        // Step 3: send each node a relative adjustment (not an absolute time, which the network would delay).
        // Work out every adjustment first, so changing the master's clock cannot skew the others.
        long[] adjust = new long[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            adjust[i] = avg - (nodes.get(i).read(trueTime) - master.read(trueTime));
        }
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).offsetMs += adjust[i];
            System.out.printf("  %-8s adjust %+5d ms%n", nodes.get(i).name, adjust[i]);
        }
        System.out.println("After sync (all clocks agree, up to the RTT/2 estimation error):");
        nodes.forEach(n -> System.out.printf("  %-8s offset %+5d ms%n", n.name, n.offsetMs));
        System.out.println("Note: Berkeley makes clocks agree with each other, not with real UTC.");
    }
}
