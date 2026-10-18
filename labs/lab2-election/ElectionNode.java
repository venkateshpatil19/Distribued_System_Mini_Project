import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

// Lab 2: coordinator election with the Bully or the Ring algorithm.
// Every node is a separate process (JVM) and nodes talk over TCP, one line per message.
// Run one node:  java ElectionNode <bully|ring> <id> <peers>
//   peers = "1:5001,2:5002,3:5003,4:5004,5:5005" (the node's own port is taken from this list)
public class ElectionNode {

    private final String mode;
    private final int id;
    private final TreeMap<Integer, Integer> ports = new TreeMap<>(); // node id -> port

    private volatile Integer leader = null;
    private volatile boolean electing = false;
    private volatile long okReceivedAt = -1;

    public ElectionNode(String mode, int id, String peers) {
        this.mode = mode;
        this.id = id;
        for (String p : peers.split(",")) {
            String[] kv = p.trim().split(":");
            ports.put(Integer.parseInt(kv[0]), Integer.parseInt(kv[1]));
        }
        if (!ports.containsKey(id)) throw new IllegalArgumentException("id " + id + " is not in the peer list");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !(args[0].equals("bully") || args[0].equals("ring"))) {
            System.out.println("Usage: java ElectionNode <bully|ring> <id> <id:port,id:port,...>");
            return;
        }
        new ElectionNode(args[0], Integer.parseInt(args[1]), args[2]).start();
    }

    private void log(String msg) {
        System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                + " [node " + id + "] " + msg);
    }

    void start() throws Exception {
        ServerSocket server = new ServerSocket(ports.get(id));
        log("up in " + mode + " mode on port " + ports.get(id));
        var pool = Executors.newCachedThreadPool();
        pool.submit(() -> {
            while (true) {
                Socket s = server.accept();
                pool.submit(() -> handle(s));
            }
        });
        // Give the other processes a moment to start listening.
        Thread.sleep(1500);
        while (true) {
            monitor();
            Thread.sleep(1000);
        }
    }

    // Failure detection: ping the leader every second; no answer means it is presumed dead.
    private void monitor() {
        if (electing) {
            // Bully: a higher node said OK but never announced itself, so it probably died too.
            if (mode.equals("bully") && okReceivedAt > 0 && System.currentTimeMillis() - okReceivedAt > 3000) {
                log("no COORDINATOR after OK, restarting election");
                startElection();
            }
            return;
        }
        if (leader == null) {
            startElection();
        } else if (leader != id && send(leader, "PING " + id) == null) {
            log("leader " + leader + " is not responding");
            leader = null;
            startElection();
        }
    }

    // Sends one line to a node and returns its one-line reply, or null if it is unreachable.
    private String send(int to, String msg) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", ports.get(to)), 500);
            s.setSoTimeout(1000);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println(msg);
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private void handle(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String line = in.readLine();
            if (line == null) return;
            String[] p = line.split(" ");
            switch (p[0]) {
                case "PING":
                    out.println("PONG");
                    break;
                case "ELECTION":
                    out.println("OK");
                    onElection(p[1]);
                    break;
                case "COORDINATOR":
                    out.println("ACK");
                    onCoordinator(Integer.parseInt(p[1]), p.length > 2 ? Integer.parseInt(p[2]) : -1);
                    break;
                default:
                    out.println("ERR");
            }
        } catch (IOException ignored) {
            // The sender gave up; it will treat us as unreachable and retry.
        }
    }

    private synchronized void startElection() {
        electing = true;
        okReceivedAt = -1;
        if (mode.equals("bully")) bullyElection();
        else ringElection();
    }

    // ---------- Bully ----------

    private void bullyElection() {
        List<Integer> higher = ports.keySet().stream().filter(n -> n > id).collect(Collectors.toList());
        log("starts BULLY election, asking higher nodes " + higher);
        boolean anyOk = false;
        for (int n : higher) {
            if ("OK".equals(send(n, "ELECTION " + id))) {
                log("got OK from " + n);
                anyOk = true;
            }
        }
        if (anyOk) {
            // A higher node will take over; wait for its COORDINATOR message.
            okReceivedAt = System.currentTimeMillis();
        } else {
            becomeLeader();
        }
    }

    private void becomeLeader() {
        leader = id;
        electing = false;
        log("*** I am the LEADER ***");
        for (int n : ports.keySet()) {
            if (n != id) send(n, "COORDINATOR " + id);
        }
    }

    // ---------- Ring ----------

    // Next live node clockwise (ids in increasing order, wrapping around), skipping dead ones.
    private Integer nextAlive() {
        List<Integer> ids = new ArrayList<>(ports.keySet());
        int i = ids.indexOf(id);
        for (int k = 1; k < ids.size(); k++) {
            int n = ids.get((i + k) % ids.size());
            if (send(n, "PING " + id) != null) return n;
        }
        return null;
    }

    private void ringElection() {
        log("starts RING election");
        forwardRing("ELECTION " + id);
    }

    private void forwardRing(String msg) {
        Integer next = nextAlive();
        if (next == null) {
            // Alone in the ring.
            becomeLeaderRing();
            return;
        }
        send(next, msg);
    }

    private void becomeLeaderRing() {
        leader = id;
        electing = false;
        log("*** I am the LEADER (alone) ***");
    }

    // ---------- message handlers ----------

    private void onElection(String payload) {
        if (mode.equals("bully")) {
            // A lower node started an election: we answered OK, now we run our own.
            new Thread(this::startElection).start();
            return;
        }
        // Ring: payload is the list of ids collected so far, e.g. "2,3,4".
        List<Integer> seen = Arrays.stream(payload.split(",")).map(Integer::parseInt).collect(Collectors.toList());
        new Thread(() -> {
            if (seen.get(0) == id) {
                // Our message went all the way round: the highest id seen wins.
                int winner = seen.stream().max(Integer::compare).get();
                log("ring complete " + seen + " -> leader is " + winner);
                leader = winner;
                electing = false;
                forwardRing("COORDINATOR " + winner + " " + id);
            } else {
                electing = true;
                log("passes ELECTION on, list " + seen + " + " + id);
                forwardRing("ELECTION " + payload + "," + id);
            }
        }).start();
    }

    private void onCoordinator(int newLeader, int origin) {
        if (mode.equals("bully") && newLeader < id) {
            // A lower node claims leadership while we are alive: bully it.
            log("rejects COORDINATOR from lower node " + newLeader);
            new Thread(this::startElection).start();
            return;
        }
        if (leader == null || leader != newLeader) log("new leader is " + newLeader);
        leader = newLeader;
        electing = false;
        // Ring: keep the announcement moving until it is back at the node that started it.
        if (mode.equals("ring") && origin != id) {
            new Thread(() -> forwardRing("COORDINATOR " + newLeader + " " + origin)).start();
        }
    }
}
