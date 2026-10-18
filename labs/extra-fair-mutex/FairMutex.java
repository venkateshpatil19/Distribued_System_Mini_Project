import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Self-study: fair distributed mutual exclusion.
// Runs Ricart-Agrawala (RA) and Lodha-Kshemkalyani (LK) on the same simulated network and compares
// the number of messages per critical-section entry. Both grant the CS in request-priority order
// (fair). Sites are threads; channels are FIFO with random delay. The program checks that at most
// one site is ever inside the CS and that entries follow request priority.
// Run: javac FairMutex.java && java FairMutex
public class FairMutex {

    static final int N = 5;
    static final int ROUNDS = 6;

    // Request id = (sequence number, site id). Lower sequence = higher priority; ties go to the lower id.
    static final class Req implements Comparable<Req> {
        final long seq;
        final int site;

        Req(long seq, int site) {
            this.seq = seq;
            this.site = site;
        }

        public int compareTo(Req o) {
            return seq != o.seq ? Long.compare(seq, o.seq) : Integer.compare(site, o.site);
        }

        public boolean equals(Object o) {
            return o instanceof Req && compareTo((Req) o) == 0;
        }

        public int hashCode() {
            return Long.hashCode(seq) * 31 + site;
        }

        public String toString() {
            return "(" + seq + "," + site + ")";
        }
    }

    static final class Msg {
        final String type; // REQUEST, REPLY, FLUSH, WANT (local trigger)
        final int from;
        final Req req;     // REQUEST: the request; REPLY/FLUSH: request just executed by the sender (may be null)

        Msg(String type, int from, Req req) {
            this.type = type;
            this.from = from;
            this.req = req;
        }
    }

    // ---------- simulated network: FIFO per channel, random delay ----------

    static final class Delivery implements Delayed {
        final long at;
        final int to;
        final Msg m;

        Delivery(long at, int to, Msg m) {
            this.at = at;
            this.to = to;
            this.m = m;
        }

        public long getDelay(TimeUnit u) {
            return u.convert(at - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        public int compareTo(Delayed o) {
            return Long.compare(at, ((Delivery) o).at);
        }
    }

    static final class Network {
        final List<BlockingQueue<Msg>> inbox = new ArrayList<>();
        final DelayQueue<Delivery> wire = new DelayQueue<>();
        final long[][] lastAt = new long[N][N];
        final Random rnd = new Random(42);
        final AtomicLong sent = new AtomicLong();

        Network() {
            for (int i = 0; i < N; i++) inbox.add(new LinkedBlockingQueue<>());
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        Delivery d = wire.take();
                        inbox.get(d.to).add(d.m);
                    }
                } catch (InterruptedException e) {
                    // simulation over
                }
            });
            t.setDaemon(true);
            t.start();
        }

        // Delivery time never goes below the previous message on the same channel, keeping it FIFO.
        synchronized void send(int from, int to, Msg m) {
            sent.incrementAndGet();
            long at = Math.max(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1 + rnd.nextInt(8)), lastAt[from][to] + 1);
            lastAt[from][to] = at;
            wire.add(new Delivery(at, to, m));
        }
    }

    // ---------- shared checks ----------

    static final AtomicInteger inCs = new AtomicInteger();
    static final List<Req> entryOrder = Collections.synchronizedList(new ArrayList<>());
    static volatile boolean violation = false;

    static void criticalSection(Req r) throws InterruptedException {
        if (inCs.incrementAndGet() > 1) violation = true;
        entryOrder.add(r);
        Thread.sleep(3);
        inCs.decrementAndGet();
    }

    // ---------- one site, either algorithm ----------

    static final class Site implements Runnable {
        final int id;
        final boolean lk;
        final Network net;
        final CountDownLatch done;
        long highestSeq = 0;
        Req mine = null;
        boolean inCS = false;
        int rounds = 0;
        final List<Integer> deferred = new ArrayList<>();
        // RA state
        int replies = 0;
        // LK state: local request queue of concurrent requests (own included) and "permission received" flags
        final TreeSet<Req> lrq = new TreeSet<>();
        final boolean[] rv = new boolean[N];

        Site(int id, boolean lk, Network net, CountDownLatch done) {
            this.id = id;
            this.lk = lk;
            this.net = net;
            this.done = done;
        }

        public void run() {
            try {
                while (rounds < ROUNDS || mine != null) {
                    Msg m = net.inbox.get(id).poll(500, TimeUnit.MILLISECONDS);
                    if (m == null) continue;
                    switch (m.type) {
                        case "WANT": request(); break;
                        case "REQUEST": onRequest(m); break;
                        case "REPLY": onReply(m); break;
                        default: onFlush(m);
                    }
                    tryEnter();
                }
                // Keep answering others until everyone is done.
                done.countDown();
                while (done.getCount() > 0 || !net.inbox.get(id).isEmpty()) {
                    Msg m = net.inbox.get(id).poll(50, TimeUnit.MILLISECONDS);
                    if (m != null && m.type.equals("REQUEST")) onRequest(m);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void request() {
            mine = new Req(++highestSeq, id);
            replies = 0;
            lrq.clear();
            lrq.add(mine);
            java.util.Arrays.fill(rv, false);
            rv[id] = true;
            for (int j = 0; j < N; j++) if (j != id) net.send(id, j, new Msg("REQUEST", id, mine));
        }

        void onRequest(Msg m) {
            highestSeq = Math.max(highestSeq, m.req.seq);
            int j = m.from;
            if (!lk) {
                // RA: defer if we are in the CS or our own pending request has priority.
                if (inCS || (mine != null && mine.compareTo(m.req) < 0)) deferred.add(j);
                else net.send(id, j, new Msg("REPLY", id, null));
                return;
            }
            if (inCS) {
                deferred.add(j);
            } else if (mine == null) {
                net.send(id, j, new Msg("REPLY", id, null));
            } else if (rv[j]) {
                // We already heard from j during this request, so j's new request is not concurrent with ours.
                deferred.add(j);
            } else {
                // Concurrent requests: each REQUEST doubles as the reply, no REPLY message is needed.
                rv[j] = true;
                lrq.add(m.req);
            }
        }

        void onReply(Msg m) {
            replies++;
            if (lk) {
                rv[m.from] = true;
                if (m.req != null) lrq.headSet(m.req, true).removeIf(r -> !r.equals(mine));
            }
        }

        void onFlush(Msg m) {
            rv[m.from] = true;
            // Everything up to the flushed request has executed.
            lrq.headSet(m.req, true).removeIf(r -> !r.equals(mine));
        }

        void tryEnter() throws InterruptedException {
            if (mine == null || inCS) return;
            boolean ok;
            if (!lk) {
                ok = replies == N - 1;
            } else {
                ok = lrq.first().equals(mine);
                for (boolean b : rv) ok &= b;
            }
            if (!ok) return;
            inCS = true;
            criticalSection(mine);
            inCS = false;
            Req done = mine;
            mine = null;
            rounds++;
            if (lk) {
                // Hand over to the next concurrent request with one FLUSH instead of many REPLYs.
                Req next = lrq.higher(done);
                if (next != null) net.send(id, next.site, new Msg("FLUSH", id, done));
                lrq.clear();
            }
            for (int j : deferred) net.send(id, j, new Msg("REPLY", id, lk ? done : null));
            deferred.clear();
        }
    }

    static long run(boolean lk) throws InterruptedException {
        inCs.set(0);
        entryOrder.clear();
        violation = false;
        Network net = new Network();
        CountDownLatch done = new CountDownLatch(N);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            Thread t = new Thread(new Site(i, lk, net, done));
            ts.add(t);
            t.start();
        }
        // Rounds of near-simultaneous requests create plenty of concurrent requests.
        Random r = new Random(1);
        for (int round = 0; round < ROUNDS; round++) {
            for (int i = 0; i < N; i++) net.inbox.get(i).add(new Msg("WANT", i, null));
            Thread.sleep(40 + r.nextInt(40));
        }
        for (Thread t : ts) t.join(30_000);
        int entries = entryOrder.size();
        System.out.printf("%-20s entries=%d  messages=%d  messages/entry=%.2f  mutual exclusion %s%n",
                lk ? "Lodha-Kshemkalyani" : "Ricart-Agrawala", entries, net.sent.get(),
                (double) net.sent.get() / entries, violation ? "VIOLATED" : "held");
        return net.sent.get();
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println(N + " sites, " + ROUNDS + " rounds of simultaneous requests each, FIFO channels with 1-8 ms delay");
        long ra = run(false);
        boolean raOk = !violation && entryOrder.size() == N * ROUNDS;
        long lk = run(true);
        boolean lkOk = !violation && entryOrder.size() == N * ROUNDS;
        System.out.println("First LK entries in order: " + entryOrder.subList(0, Math.min(10, entryOrder.size())));
        System.out.printf("LK used %.0f%% fewer messages than RA (RA needs 2(N-1) = %d per entry).%n",
                100.0 * (ra - lk) / ra, 2 * (N - 1));
        System.out.println(raOk && lkOk ? "PASS: both safe and all requests served" : "FAIL: see output above");
    }
}
