package com.bi4byf.n7500link.gateway;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Single serial GAIA request scheduler with named read-only entry points only. */
public final class CommandScheduler {
    public interface Transport {
        boolean isReady(int epoch);
        void send(int epoch, byte[] frame) throws IOException;
    }

    public interface Listener {
        void onReadResult(ReadResult result);
        void onTransportFault(int epoch, String code);
    }

    public static final class ReadKind {
        public final String name;
        public final int command;
        public final boolean expectsResponse;
        private ReadKind(String name, int command, boolean expectsResponse) {
            this.name = name;
            this.command = command;
            this.expectsResponse = expectsResponse;
        }
    }

    public static final ReadKind DEVICE_INFO =
            new ReadKind("DEVICE_INFO", GaiaCodec.CMD_GET_DEV_INFO, true);
    public static final ReadKind NOTIFICATION_REGISTRATION =
            new ReadKind("NOTIFICATION_REGISTRATION",
                    GaiaCodec.CMD_REGISTER_NOTIFICATION, false);
    public static final ReadKind SETTINGS =
            new ReadKind("SETTINGS", GaiaCodec.CMD_READ_SETTINGS, true);
    public static final ReadKind STATUS =
            new ReadKind("STATUS", GaiaCodec.CMD_GET_HT_STATUS, true);
    public static final ReadKind VOLUME =
            new ReadKind("VOLUME", GaiaCodec.CMD_GET_VOLUME, true);
    public static final ReadKind CHANNEL =
            new ReadKind("CHANNEL", GaiaCodec.CMD_READ_RF_CH, true);

    public static final class ReadResult {
        public final ReadKind kind;
        public final int epoch;
        public final RadioParser.Parsed parsed;
        public final boolean timedOut;
        public final boolean stale;
        public final long elapsedMs;

        private ReadResult(ReadKind kind, int epoch, RadioParser.Parsed parsed,
                           boolean timedOut, boolean stale, long elapsedMs) {
            this.kind = kind;
            this.epoch = epoch;
            this.parsed = parsed;
            this.timedOut = timedOut;
            this.stale = stale;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class Pending {
        final ReadKind kind;
        final int epoch;
        final byte[] frame;
        final long submittedAt;

        Pending(ReadKind kind, int epoch, byte[] frame) {
            this.kind = kind;
            this.epoch = epoch;
            this.frame = frame;
            this.submittedAt = System.currentTimeMillis();
        }
    }

    private static final class TaggedResponse {
        final int epoch;
        final byte[] body;
        TaggedResponse(int epoch, byte[] body) { this.epoch = epoch; this.body = body; }
    }

    private final Listener listener;
    private final Transport transport;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private final LinkedBlockingQueue<TaggedResponse> responses = new LinkedBlockingQueue<>();
    private volatile int currentEpoch;
    private volatile boolean shutdown;
    private long completed;
    private long timedOut;
    private long merged;
    private long sent;
    private long matched;

    public CommandScheduler(Listener listener, Transport transport) {
        this.listener = listener;
        this.transport = transport;
    }

    public synchronized int startEpoch(int epoch) {
        if (epoch <= 0) throw new IllegalArgumentException("epoch must be positive");
        currentEpoch = epoch;
        pending.clear();
        responses.clear();
        return epoch;
    }

    public boolean requestDeviceInfo() { return enqueue(DEVICE_INFO, GaiaCodec.deviceInfoRequest()); }
    public boolean requestNotificationRegistration() {
        return enqueue(NOTIFICATION_REGISTRATION, GaiaCodec.notificationRegistrationRequest());
    }
    public boolean requestSettings() { return enqueue(SETTINGS, GaiaCodec.settingsRequest()); }
    public boolean requestStatus() { return enqueue(STATUS, GaiaCodec.statusRequest()); }
    public boolean requestVolume() { return enqueue(VOLUME, GaiaCodec.volumeRequest()); }
    public boolean requestChannel(int channelId) {
        return enqueue(CHANNEL, GaiaCodec.channelRequest(channelId));
    }

    /** The epoch is captured by the Bluetooth receive callback, never inferred later. */
    public void onResponse(int epoch, byte[] body) {
        if (body == null || shutdown || epoch != currentEpoch) return;
        responses.offer(new TaggedResponse(epoch, body));
    }

    public void shutdown() {
        shutdown = true;
        worker.shutdownNow();
        synchronized (this) { pending.clear(); }
        responses.clear();
    }

    public synchronized long completedCount() { return completed; }
    public synchronized long timeoutCount() { return timedOut; }
    public synchronized long mergedCount() { return merged; }
    public synchronized long sentCount() { return sent; }
    public synchronized long matchedCount() { return matched; }
    public synchronized int pendingCount() { return pending.size(); }

    private boolean enqueue(ReadKind kind, byte[] frame) {
        Pending value;
        synchronized (this) {
            if (shutdown || currentEpoch <= 0) return false;
            if (pending.containsKey(kind.command)) {
                merged++;
                return false;
            }
            value = new Pending(kind, currentEpoch, frame);
            pending.put(kind.command, value);
        }
        try {
            worker.execute(() -> execute(value));
            return true;
        } catch (RuntimeException e) {
            synchronized (this) { pending.remove(kind.command); }
            return false;
        }
    }

    private void execute(Pending request) {
        long started = System.currentTimeMillis();
        if (shutdown || request.epoch != currentEpoch) {
            finish(request, new ReadResult(request.kind, request.epoch, null, false, true,
                    System.currentTimeMillis() - started));
            return;
        }
        if (request.epoch != currentEpoch) {
            finish(request, new ReadResult(request.kind, request.epoch, null, false, true,
                    System.currentTimeMillis() - started));
            return;
        }
        if (!transport.isReady(request.epoch)) {
            finish(request, new ReadResult(request.kind, request.epoch, null, false, false,
                    System.currentTimeMillis() - started));
            listener.onTransportFault(request.epoch, "BLUETOOTH_NOT_READY");
            return;
        }

        if (request.epoch != currentEpoch) {
            finish(request, new ReadResult(request.kind, request.epoch, null, false, true,
                    System.currentTimeMillis() - started));
            return;
        }
        try {
            transport.send(request.epoch, request.frame);
            synchronized (this) { sent++; }
        } catch (IOException e) {
            finish(request, new ReadResult(request.kind, request.epoch, null, false, false,
                    System.currentTimeMillis() - started));
            listener.onTransportFault(request.epoch, "BLUETOOTH_SEND_FAILED");
            return;
        }

        // REGISTER_NOTIFICATION is acknowledged by an unsolicited event, not a normal
        // command response. Do not block the scheduler or count a false timeout.
        if (!request.kind.expectsResponse) {
            finish(request, new ReadResult(request.kind, request.epoch, null, false, false,
                    System.currentTimeMillis() - started));
            return;
        }

        long deadline = started + 2000L;
        RadioParser.Parsed parsed = null;
        boolean stale = false;
        while (System.currentTimeMillis() < deadline && !shutdown) {
            try {
                long wait = Math.max(1L, deadline - System.currentTimeMillis());
                TaggedResponse response = responses.poll(wait, TimeUnit.MILLISECONDS);
                if (response == null) break;
                if (response.epoch != request.epoch) {
                    stale = true;
                    continue;
                }
                if (!GaiaCodec.isResponse(response.body)) continue;
                if (GaiaCodec.command(response.body) != request.kind.command) continue;
                parsed = RadioParser.parseResponse(response.body);
                synchronized (this) { matched++; }
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        long elapsed = System.currentTimeMillis() - started;
        boolean wasTimeout = parsed == null && !shutdown;
        if (wasTimeout) synchronized (this) { timedOut++; }
        finish(request, new ReadResult(request.kind, request.epoch, parsed,
                wasTimeout, stale, elapsed));
    }

    private void finish(Pending request, ReadResult result) {
        boolean staleEpoch = request.epoch != currentEpoch;
        synchronized (this) {
            pending.remove(request.kind.command);
            if (!result.stale && !result.timedOut && !staleEpoch) completed++;
        }
        if (!shutdown && !staleEpoch) listener.onReadResult(result);
    }
}
