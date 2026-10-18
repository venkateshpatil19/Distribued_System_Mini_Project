import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

// Lab 1 client. Sends commands to FlagServer and prints the replies.
// Interactive:  java FlagClient localhost 5050
// Scripted:     java FlagClient localhost 5050 "SET new-checkout true" "GET new-checkout" LIST
public class FlagClient {

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5050;

        try (Socket socket = new Socket()) {
            // Fail fast instead of hanging if the server is down.
            socket.connect(new InetSocketAddress(host, port), 3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            System.out.println(in.readLine());

            if (args.length > 2) {
                for (int i = 2; i < args.length; i++) send(args[i], in, out);
                send("QUIT", in, out);
                return;
            }

            BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("Commands: GET <key>, SET <key> <value>, DEL <key>, LIST, VERSION, QUIT");
            String line;
            while ((line = keyboard.readLine()) != null) {
                if (send(line, in, out).equals("BYE")) break;
            }
        }
    }

    private static String send(String cmd, BufferedReader in, PrintWriter out) throws IOException {
        out.println(cmd);
        String reply = in.readLine();
        if (reply == null) throw new IOException("server closed the connection");
        System.out.println("> " + cmd + "\n  " + reply);
        return reply;
    }
}
