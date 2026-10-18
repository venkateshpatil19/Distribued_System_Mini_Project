import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

// Lab 4: Lamport and vector clocks.
// Three processes run as threads and exchange messages through queues. Each event prints its
// Lamport time and vector time. At the end every pair of events is classified as
// "happened before" or "concurrent", showing what vector clocks can tell that Lamport clocks cannot.
// Run: javac LogicalClocks.java && java LogicalClocks
public class LogicalClocks {

    static final int N = 3;
    static final List<BlockingQueue<int[]>> inbox = new ArrayList<>();
    static final List<Event> events = new ArrayList<>();

    static final class Event {
        final String name;
        final int process;
        final int lamport;
        final int[] vector;

        Event(String name, int process, int lamport, int[] vector) {
            this.name = name;
            this.process = process;
            this.lamport = lamport;
            this.vector = vector.clone();
        }
    }

    static final class Process implements Runnable {
        final int id;
        final String[] script;
        int lamport = 0;
        final int[] vector = new int[N];

        Process(int id, String... script) {
            this.id = id;
            this.script = script;
        }

        // Script steps: "local:a", "send:b:2" (event b sends to P2), "recv:c" (event c receives).
        public void run() {
            try {
                for (String step : script) {
                    String[] s = step.split(":");
                    switch (s[0]) {
                        case "local":
                            tick();
                            record(s[1]);
                            break;
                        case "send":
                            tick();
                            record(s[1]);
                            // The message carries the sender's clocks: [lamport, v0, v1, v2].
                            int[] msg = new int[N + 1];
                            msg[0] = lamport;
                            System.arraycopy(vector, 0, msg, 1, N);
                            inbox.get(Integer.parseInt(s[2]) - 1).put(msg);
                            break;
                        case "recv":
                            int[] m = inbox.get(id - 1).take();
                            // Lamport: max(local, received) + 1. Vector: element-wise max, then tick own entry.
                            lamport = Math.max(lamport, m[0]) + 1;
                            for (int i = 0; i < N; i++) vector[i] = Math.max(vector[i], m[i + 1]);
                            vector[id - 1]++;
                            record(s[1]);
                            break;
                        default:
                            throw new IllegalArgumentException(step);
                    }
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void tick() {
            lamport++;
            vector[id - 1]++;
        }

        private void record(String name) {
            Event e = new Event(name, id, lamport, vector);
            synchronized (events) {
                events.add(e);
            }
            System.out.printf("P%d  %-3s  Lamport=%-2d  vector=%s%n", id, name, lamport, Arrays.toString(vector));
        }
    }

    // a -> b (happened before) when V(a) <= V(b) element-wise and V(a) != V(b).
    static boolean happenedBefore(Event a, Event b) {
        boolean strictlyLess = false;
        for (int i = 0; i < N; i++) {
            if (a.vector[i] > b.vector[i]) return false;
            if (a.vector[i] < b.vector[i]) strictlyLess = true;
        }
        return strictlyLess;
    }

    public static void main(String[] args) throws InterruptedException {
        for (int i = 0; i < N; i++) inbox.add(new LinkedBlockingQueue<>());
        // A controller (P1) pushes a config revision to two agents (P2, P3), which acknowledge it.
        Thread p1 = new Thread(new Process(1, "local:a", "send:b:2", "send:c:3", "recv:d", "local:e"));
        Thread p2 = new Thread(new Process(2, "local:f", "recv:g", "send:h:1", "local:i"));
        Thread p3 = new Thread(new Process(3, "local:j", "local:k", "recv:l", "local:m"));
        p1.start();
        p2.start();
        p3.start();
        p1.join();
        p2.join();
        p3.join();

        System.out.println("\nCausality between events (from vector clocks):");
        int concurrent = 0;
        int misleading = 0;
        for (int i = 0; i < events.size(); i++) {
            for (int j = i + 1; j < events.size(); j++) {
                Event a = events.get(i);
                Event b = events.get(j);
                String rel;
                if (happenedBefore(a, b)) rel = a.name + " -> " + b.name;
                else if (happenedBefore(b, a)) rel = b.name + " -> " + a.name;
                else {
                    rel = a.name + " || " + b.name + "  (concurrent)";
                    concurrent++;
                    // Lamport gives these different numbers even though neither caused the other.
                    if (a.lamport != b.lamport) misleading++;
                }
                if (a.process != b.process) System.out.println("  " + rel);
            }
        }
        System.out.println("\nConcurrent pairs: " + concurrent + ", of which " + misleading
                + " have different Lamport times.");
        System.out.println("Lamport: a -> b implies L(a) < L(b), but L(a) < L(b) does NOT imply a -> b.");
        System.out.println("Vector:  a -> b if and only if V(a) < V(b).");
    }
}
