package com.deployguard.controller;

import com.deployguard.common.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

// Server-Sent Events fan-out to dashboard clients. Event ids are de-duplicated because the same
// event can reach this controller twice (recorded locally and seen again in a replicated copy).
public final class Sse {
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 5000;
        }
    });

    private static final class Client {
        final OutputStream out;
        final CountDownLatch closed = new CountDownLatch(1);

        Client(OutputStream out) {
            this.out = out;
        }
    }

    public int clientCount() {
        return clients.size();
    }

    // Returns false when this event id was already broadcast.
    public boolean broadcast(String id, String type, Object payload) {
        synchronized (seen) {
            if (id != null && !seen.add(id)) return false;
        }
        String frame = "id: " + id + "\nevent: " + type + "\ndata: " + Json.write(payload) + "\n\n";
        send(frame);
        return true;
    }

    public void ping() {
        send(": ping\n\n");
    }

    private void send(String frame) {
        byte[] b = frame.getBytes(StandardCharsets.UTF_8);
        for (Client c : clients) {
            try {
                synchronized (c) {
                    c.out.write(b);
                    c.out.flush();
                }
            } catch (IOException e) {
                clients.remove(c);
                c.closed.countDown();
            }
        }
    }

    // Holds the HTTP exchange open until the client disconnects.
    public void attach(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        Client c = new Client(ex.getResponseBody());
        clients.add(c);
        synchronized (c) {
            c.out.write("retry: 3000\n: connected\n\n".getBytes(StandardCharsets.UTF_8));
            c.out.flush();
        }
        try {
            c.closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(c);
            ex.close();
        }
    }
}
