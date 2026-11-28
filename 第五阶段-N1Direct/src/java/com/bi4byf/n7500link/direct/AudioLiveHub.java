package com.bi4byf.n7500link.direct;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded live PCM fan-out. Each subscriber owns a separate queue, so one slow
 * browser can only lose its own frames and never blocks recording or peers.
 */
final class AudioLiveHub {
    static final int DEFAULT_QUEUE_CAPACITY = 32;
    static final int DEFAULT_MAX_CLIENTS = 4;

    static final class Client {
        private final long id;
        private final BlockingQueue<byte[]> queue;
        private volatile boolean closed;
        private volatile long droppedFrames;

        Client(long id, int capacity) {
            this.id = id;
            this.queue = new LinkedBlockingQueue<>(capacity);
        }

        long id() { return id; }
        boolean isClosed() { return closed; }
        long droppedFrames() { return droppedFrames; }

        byte[] next(long timeoutMs) throws InterruptedException {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<Client> clients = new ArrayList<>();
    private final int queueCapacity;
    private final int maxClients;

    AudioLiveHub() {
        this(DEFAULT_QUEUE_CAPACITY, DEFAULT_MAX_CLIENTS);
    }

    AudioLiveHub(int queueCapacity, int maxClients) {
        if (queueCapacity < 2) throw new IllegalArgumentException("queueCapacity");
        if (maxClients < 1) throw new IllegalArgumentException("maxClients");
        this.queueCapacity = queueCapacity;
        this.maxClients = maxClients;
    }

    Client subscribe() {
        synchronized (clients) {
            if (clients.size() >= maxClients) return null;
            Client client = new Client(nextId.getAndIncrement(), queueCapacity);
            clients.add(client);
            return client;
        }
    }

    void broadcast(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        List<Client> copy;
        synchronized (clients) {
            if (clients.isEmpty()) return;
            copy = new ArrayList<>(clients);
        }
        for (Client client : copy) {
            if (client.closed) continue;
            if (!client.queue.offer(pcm)) {
                client.droppedFrames++;
            }
        }
    }

    void close(Client client) {
        if (client == null) return;
        client.closed = true;
        client.queue.clear();
        synchronized (clients) {
            clients.remove(client);
        }
    }

    void closeAll() {
        List<Client> copy;
        synchronized (clients) {
            copy = new ArrayList<>(clients);
            clients.clear();
        }
        for (Client client : copy) {
            client.closed = true;
            client.queue.clear();
        }
    }

    int clientCount() {
        synchronized (clients) { return clients.size(); }
    }
}