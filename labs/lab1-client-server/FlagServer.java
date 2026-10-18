import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

// Lab 1: a tiny feature-flag configuration server over TCP sockets.
// Protocol (one text line per request):
//   GET <key> | SET <key> <value> | DEL <key> | LIST | VERSION | QUIT
// Run: javac FlagServer.java && java FlagServer 5050
public class FlagServer {

    private static final Map<String, String> flags = new ConcurrentHashMap<>();
    // Every change bumps the version, like a config revision in DeployGuard.
    private static final AtomicLong version = new AtomicLong(0);

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5050;
        flags.put("new-checkout", "false");
        flags.put("search-ranker", "bm25");

        // A thread pool lets many clients connect at the same time.
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("FlagServer listening on port " + port);
            while (true) {
                Socket client = server.accept();
                pool.submit(() -> serve(client));
            }
        }
    }

    private static void serve(Socket socket) {
        String who = socket.getRemoteSocketAddress().toString();
        System.out.println("connected: " + who);
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
            // Close idle clients after 60 s so a stuck client cannot hold a thread forever.
            socket.setSoTimeout(60_000);
            out.println("OK FlagServer ready, version " + version.get());
            String line;
            while ((line = in.readLine()) != null) {
                String reply = handle(line.trim());
                out.println(reply);
                System.out.println(who + " > " + line + "  =>  " + reply);
                if (reply.equals("BYE")) break;
            }
        } catch (IOException e) {
            System.out.println("client " + who + " dropped: " + e.getMessage());
        }
    }

    // Parses one request line and returns the reply line.
    static String handle(String line) {
        if (line.isEmpty()) return "ERR empty request";
        String[] p = line.split("\\s+", 3);
        String cmd = p[0].toUpperCase();
        switch (cmd) {
            case "GET":
                if (p.length < 2) return "ERR usage: GET <key>";
                String v = flags.get(p[1]);
                return v == null ? "NOT_FOUND " + p[1] : "VALUE " + p[1] + " " + v;
            case "SET":
                if (p.length < 3) return "ERR usage: SET <key> <value>";
                if (!p[1].matches("[a-z0-9-]{1,64}")) return "ERR key must be lowercase letters, digits or '-'";
                flags.put(p[1], p[2]);
                return "OK version " + version.incrementAndGet();
            case "DEL":
                if (p.length < 2) return "ERR usage: DEL <key>";
                return flags.remove(p[1]) == null ? "NOT_FOUND " + p[1] : "OK version " + version.incrementAndGet();
            case "LIST":
                return "FLAGS " + new TreeMap<>(flags);
            case "VERSION":
                return "VERSION " + version.get();
            case "QUIT":
                return "BYE";
            default:
                return "ERR unknown command " + cmd;
        }
    }
}
