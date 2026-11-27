package com.bi4byf.n7500link.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded SSE fan-out. Event data contains only snapshot identity and revision. */
public final class SseHub {
    private static final int MAX_CLIENTS = 4;
    private static final int QUEUE_CAPACITY = 16;

    public static final class Client {
        private final long id;
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        private volatile boolean closed;

        private Client(long id) { this.id = id; }
        public long id() { return id; }
        public boolean isClosed() { return closed; }

        public String next(long timeoutMs) throws InterruptedException {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<Client> clients = new ArrayList<>();

    public Client subscribe(String instanceId, long revision, Long updatedAtMs,
                            boolean stale) {
        Client client;
        synchronized (clients) {
            if (clients.size() >= MAX_CLIENTS) return null;
            client = new Client(nextId.getAndIncrement());
            clients.add(client);
        }
        client.queue.offer(ready(instanceId, revision));
        return client;
    }

    public void broadcastSnapshot(String instanceId, long revision,
                                  Long updatedAtMs, boolean stale) {
        List<Client> copy;
        synchronized (clients) {
            copy = new ArrayList<>(clients);
        }
        String event = snapshot(instanceId, revision, updatedAtMs, stale);
        for (Client client : copy) {
            if (!client.queue.offer(event)) {
                close(client);
            }
        }
    }

    public void broadcastHeartbeat(long nowMs) {
        List<Client> copy;
        synchronized (clients) {
            copy = new ArrayList<>(clients);
        }
        String event = "event: heartbeat\ndata: {\"at\":"
                + JsonUtil.quote(GatewayContract.utc(nowMs)) + "}\n\n";
        for (Client client : copy) {
            if (!client.queue.offer(event)) close(client);
        }
    }

    public void close(Client client) {
        if (client == null) return;
        client.closed = true;
        client.queue.offer("event: close\ndata: {}\n\n");
        synchronized (clients) {
            clients.remove(client);
        }
    }

    public void closeAll() {
        List<Client> copy;
        synchronized (clients) {
            copy = new ArrayList<>(clients);
            clients.clear();
        }
        for (Client client : copy) {
            client.closed = true;
            client.queue.offer("event: close\ndata: {}\n\n");
        }
    }

    public int clientCount() {
        synchronized (clients) { return clients.size(); }
    }

    public static String ready(String instanceId, long revision) {
        return "event: ready\nid: " + instanceId + ":" + revision
                + "\ndata: {\"instanceId\":" + JsonUtil.quote(instanceId)
                + ",\"revision\":" + revision + "}\n\n";
    }

    public static String snapshot(String instanceId, long revision,
                                  Long updatedAtMs, boolean stale) {
        return "event: snapshot\nid: " + instanceId + ":" + revision
                + "\ndata: {\"instanceId\":" + JsonUtil.quote(instanceId)
                + ",\"revision\":" + revision
                + ",\"updatedAt\":"
                + (updatedAtMs == null ? "null" : JsonUtil.quote(GatewayContract.utc(updatedAtMs)))
                + ",\"stale\":" + stale + "}\n\n";
    }
}
