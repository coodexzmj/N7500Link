package com.bi4byf.n7500link.direct;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Single serial GAIA scheduler for the explicit read list and two low-risk writes. */
public final class CommandScheduler {
    public interface Transport {
        boolean isReady(int epoch);
        void send(int epoch, byte[] frame) throws IOException;
    }

    public interface Listener {
        void onReadResult(ReadResult result);
        void onWriteResult(WriteResult result);
        void onTransportFault(int epoch, String code);
    }

    public enum ReadSource {
        CURRENT_CHANNEL,
        CATALOG
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

    public static final class WriteKind {
        public final String name;
        public final int command;
        private WriteKind(String name, int command) {
            this.name = name;
            this.command = command;
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

    public static final WriteKind VOLUME_WRITE =
            new WriteKind("VOLUME_WRITE", GaiaCodec.CMD_SET_VOLUME);
    public static final WriteKind SETTINGS_WRITE =
            new WriteKind("SETTINGS_WRITE", GaiaCodec.CMD_WRITE_SETTINGS);

    public static final class ReadResult {
        public final ReadKind kind;
        public final int epoch;
        public final ReadSource source;
        public final int targetChannelId;
        public final long requestId;
        public final RadioParser.Parsed parsed;
        public final boolean timedOut;
        public final boolean stale;
        public final String errorCode;
        public final long elapsedMs;

        private ReadResult(ReadKind kind, int epoch, ReadSource source,
                           int targetChannelId, long requestId,
                           RadioParser.Parsed parsed, boolean timedOut,
                           boolean stale, String errorCode, long elapsedMs) {
            this.kind = kind;
            this.epoch = epoch;
            this.source = source;
            this.targetChannelId = targetChannelId;
            this.requestId = requestId;
            this.parsed = parsed;
            this.timedOut = timedOut;
            this.stale = stale;
            this.errorCode = errorCode;
            this.elapsedMs = elapsedMs;
        }
    }

    public static final class WriteResult {
        public final WriteKind kind;
        public final int epoch;
        public final boolean sent;
        public final boolean timedOut;
        public final boolean stale;
        public final int responseStatus;
        public final String errorCode;
        public final long elapsedMs;

        private WriteResult(WriteKind kind, int epoch, boolean sent, boolean timedOut,
                            boolean stale, int responseStatus, String errorCode,
                            long elapsedMs) {
            this.kind = kind;
            this.epoch = epoch;
            this.sent = sent;
            this.timedOut = timedOut;
            this.stale = stale;
            this.responseStatus = responseStatus;
            this.errorCode = errorCode;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class Pending {
        final ReadKind readKind;
        final WriteKind writeKind;
        final int epoch;
        final ReadSource source;
        final int targetChannelId;
        final long requestId;
        final byte[] frame;
        final long submittedAt;

        private Pending(ReadKind readKind, WriteKind writeKind, int epoch,
                        ReadSource source, int targetChannelId, long requestId,
                        byte[] frame) {
            this.readKind = readKind;
            this.writeKind = writeKind;
            this.epoch = epoch;
            this.source = source;
            this.targetChannelId = targetChannelId;
            this.requestId = requestId;
            this.frame = frame;
            this.submittedAt = System.currentTimeMillis();
        }

        static Pending read(ReadKind kind, int epoch, ReadSource source,
                            int targetChannelId, long requestId, byte[] frame) {
            return new Pending(kind, null, epoch, source, targetChannelId,
                    requestId, frame);
        }

        static Pending write(WriteKind kind, int epoch, byte[] frame) {
            return new Pending(null, kind, epoch, null, -1, 0L, frame);
        }

        int command() { return readKind == null ? writeKind.command : readKind.command; }
        boolean isWrite() { return writeKind != null; }
    }

    private static final class TaggedResponse {
        final int epoch;
        final byte[] body;
        TaggedResponse(int epoch, byte[] body) { this.epoch = epoch; this.body = body; }
    }

    private static final long DEFAULT_RESPONSE_TIMEOUT_MS = 2000L;

    private final Listener listener;
    private final Transport transport;
    private final long responseTimeoutMs;
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
        this(listener, transport, DEFAULT_RESPONSE_TIMEOUT_MS);
    }

    CommandScheduler(Listener listener, Transport transport, long responseTimeoutMs) {
        if (listener == null || transport == null) {
            throw new IllegalArgumentException("scheduler dependencies are required");
        }
        if (responseTimeoutMs <= 0L) {
            throw new IllegalArgumentException("responseTimeoutMs must be positive");
        }
        this.listener = listener;
        this.transport = transport;
        this.responseTimeoutMs = responseTimeoutMs;
    }

    public synchronized int startEpoch(int epoch) {
        if (epoch <= 0) throw new IllegalArgumentException("epoch must be positive");
        currentEpoch = epoch;
        pending.clear();
        responses.clear();
        return epoch;
    }

    public boolean requestDeviceInfo() {
        return enqueueRead(DEVICE_INFO, GaiaCodec.deviceInfoRequest(), -1, 0L);
    }

    public boolean requestNotificationRegistration() {
        return enqueueRead(NOTIFICATION_REGISTRATION,
                GaiaCodec.notificationRegistrationRequest(), -1, 0L);
    }

    public boolean requestSettings() {
        return enqueueRead(SETTINGS, GaiaCodec.settingsRequest(), -1, 0L);
    }

    public boolean requestStatus() {
        return enqueueRead(STATUS, GaiaCodec.statusRequest(), -1, 0L);
    }

    public boolean requestVolume() {
        return enqueueRead(VOLUME, GaiaCodec.volumeRequest(), -1, 0L);
    }

    /** Ordinary current-channel read. The fixed catalog range is enforced here too. */
    public boolean requestChannel(int channelId) {
        if (channelId < 0 || channelId > 31) return false;
        return enqueueRead(CHANNEL, GaiaCodec.channelRequest(channelId),
                channelId, 0L);
    }

    /** Catalog-tagged channel read. Only this source reaches the catalog state machine. */
    public boolean requestCatalogChannel(int channelId, long requestId) {
        if (channelId < 0 || channelId > 31 || requestId <= 0L) return false;
        return enqueueRead(CHANNEL, GaiaCodec.channelRequest(channelId),
                channelId, requestId, ReadSource.CATALOG);
    }

    /** Named one-shot volume write. The scheduler never retries a write automatically. */
    public boolean requestVolumeWrite(int volume) {
        return enqueueWrite(VOLUME_WRITE, GaiaCodec.setVolumeRequest(volume));
    }

    /** Complete read-modify-write settings block. No partial or arbitrary payloads exist. */
    public boolean requestSettingsWrite(byte[] completePayload) {
        return enqueueWrite(SETTINGS_WRITE,
                GaiaCodec.writeSettingsRequest(completePayload));
    }

    public boolean isReady(int epoch) { return transport.isReady(epoch); }

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
    public synchronized boolean hasPendingCommand(int command) {
        return pending.containsKey(command);
    }

    private boolean enqueueRead(ReadKind kind, byte[] frame,
                                int targetChannelId, long requestId) {
        return enqueueRead(kind, frame, targetChannelId, requestId,
                ReadSource.CURRENT_CHANNEL);
    }

    private boolean enqueueRead(ReadKind kind, byte[] frame,
                                int targetChannelId, long requestId,
                                ReadSource source) {
        return enqueue(Pending.read(kind, currentEpochSnapshot(), source,
                targetChannelId, requestId, frame));
    }

    private boolean enqueueWrite(WriteKind kind, byte[] frame) {
        return enqueue(Pending.write(kind, currentEpochSnapshot(), frame));
    }

    private synchronized int currentEpochSnapshot() { return currentEpoch; }

    private boolean enqueue(Pending value) {
        synchronized (this) {
            if (shutdown || value.epoch <= 0) return false;
            if (pending.containsKey(value.command())) {
                merged++;
                return false;
            }
            pending.put(value.command(), value);
        }
        try {
            worker.execute(() -> {
                if (value.isWrite()) executeWrite(value); else executeRead(value);
            });
            return true;
        } catch (RuntimeException e) {
            synchronized (this) {
                if (pending.get(value.command()) == value) {
                    pending.remove(value.command());
                }
            }
            return false;
        }
    }

    private void executeRead(Pending request) {
        long started = System.currentTimeMillis();
        if (shutdown || request.epoch != currentEpoch) {
            finishRead(request, new ReadResult(request.readKind, request.epoch,
                    request.source, request.targetChannelId, request.requestId,
                    null, false, true, "STALE_EPOCH", elapsed(started)));
            return;
        }
        if (!transport.isReady(request.epoch)) {
            finishRead(request, new ReadResult(request.readKind, request.epoch,
                    request.source, request.targetChannelId, request.requestId,
                    null, false, false, "BLUETOOTH_NOT_READY", elapsed(started)));
            reportReadTransportFault(request, "BLUETOOTH_NOT_READY");
            return;
        }
        if (request.epoch != currentEpoch) {
            finishRead(request, new ReadResult(request.readKind, request.epoch,
                    request.source, request.targetChannelId, request.requestId,
                    null, false, true, "STALE_EPOCH", elapsed(started)));
            return;
        }
        try {
            transport.send(request.epoch, request.frame);
            synchronized (this) { sent++; }
        } catch (IOException e) {
            finishRead(request, new ReadResult(request.readKind, request.epoch,
                    request.source, request.targetChannelId, request.requestId,
                    null, false, false, "BLUETOOTH_SEND_FAILED", elapsed(started)));
            reportReadTransportFault(request, "BLUETOOTH_SEND_FAILED");
            return;
        }

        if (!request.readKind.expectsResponse) {
            finishRead(request, new ReadResult(request.readKind, request.epoch,
                    request.source, request.targetChannelId, request.requestId,
                    null, false, false, null, elapsed(started)));
            return;
        }

        long deadline = started + responseTimeoutMs;
        RadioParser.Parsed parsed = null;
        boolean staleResponse = false;
        while (System.currentTimeMillis() < deadline && !shutdown
                && request.epoch == currentEpoch) {
            try {
                long wait = Math.max(1L, deadline - System.currentTimeMillis());
                TaggedResponse response = responses.poll(wait, TimeUnit.MILLISECONDS);
                if (response == null) break;
                if (response.epoch != request.epoch) {
                    staleResponse = true;
                    continue;
                }
                if (!GaiaCodec.isResponse(response.body)) continue;
                if (GaiaCodec.command(response.body) != request.readKind.command) continue;
                parsed = RadioParser.parseResponse(response.body);
                synchronized (this) { matched++; }
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        boolean staleEpoch = request.epoch != currentEpoch;
        boolean stale = staleResponse || staleEpoch;
        boolean wasTimeout = parsed == null && !stale && !shutdown;
        String errorCode = null;
        if (stale) {
            errorCode = "STALE_EPOCH";
        } else if (wasTimeout) {
            synchronized (this) { timedOut++; }
            errorCode = "RESPONSE_TIMEOUT";
        }
        finishRead(request, new ReadResult(request.readKind, request.epoch,
                request.source, request.targetChannelId, request.requestId,
                parsed, wasTimeout, stale, errorCode, elapsed(started)));
    }

    private void reportReadTransportFault(Pending request, String code) {
        if (request.source != ReadSource.CATALOG) {
            listener.onTransportFault(request.epoch, code);
        }
    }

    private void executeWrite(Pending request) {
        long started = System.currentTimeMillis();
        if (shutdown || request.epoch != currentEpoch) {
            finishWrite(request, new WriteResult(request.writeKind, request.epoch,
                    false, false, true, -1, "STALE_EPOCH", elapsed(started)));
            return;
        }
        if (!transport.isReady(request.epoch)) {
            finishWrite(request, new WriteResult(request.writeKind, request.epoch,
                    false, false, false, -1, "BLUETOOTH_NOT_READY", elapsed(started)));
            listener.onTransportFault(request.epoch, "BLUETOOTH_NOT_READY");
            return;
        }
        if (request.epoch != currentEpoch) {
            finishWrite(request, new WriteResult(request.writeKind, request.epoch,
                    false, false, true, -1, "STALE_EPOCH", elapsed(started)));
            return;
        }

        try {
            transport.send(request.epoch, request.frame);
            synchronized (this) { sent++; }
        } catch (IOException e) {
            finishWrite(request, new WriteResult(request.writeKind, request.epoch,
                    false, false, false, -1, "BLUETOOTH_SEND_FAILED", elapsed(started)));
            listener.onTransportFault(request.epoch, "BLUETOOTH_SEND_FAILED");
            return;
        }

        long deadline = started + responseTimeoutMs;
        boolean matchedResponse = false;
        int responseStatus = -1;
        String errorCode = null;
        while (System.currentTimeMillis() < deadline && !shutdown
                && request.epoch == currentEpoch) {
            try {
                long wait = Math.max(1L, deadline - System.currentTimeMillis());
                TaggedResponse response = responses.poll(wait, TimeUnit.MILLISECONDS);
                if (response == null) break;
                if (response.epoch != request.epoch) continue;
                if (!GaiaCodec.isResponse(response.body)) continue;
                if (GaiaCodec.command(response.body) != request.writeKind.command) continue;
                matchedResponse = true;
                responseStatus = GaiaCodec.status(response.body);
                if (responseStatus != 0) errorCode = "RADIO_WRITE_REJECTED";
                synchronized (this) { matched++; }
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        boolean wasTimeout = !matchedResponse && !shutdown && request.epoch == currentEpoch;
        if (wasTimeout) {
            synchronized (this) { timedOut++; }
            errorCode = "WRITE_RESPONSE_TIMEOUT";
        }
        finishWrite(request, new WriteResult(request.writeKind, request.epoch, true,
                wasTimeout, request.epoch != currentEpoch, responseStatus, errorCode,
                elapsed(started)));
    }

    private void finishRead(Pending request, ReadResult result) {
        boolean staleEpoch = request.epoch != currentEpoch;
        synchronized (this) {
            if (pending.get(request.command()) == request) {
                pending.remove(request.command());
            }
            if (!result.stale && !result.timedOut && !staleEpoch) completed++;
        }
        if (!shutdown && !staleEpoch && !result.stale) {
            listener.onReadResult(result);
        }
    }

    private void finishWrite(Pending request, WriteResult result) {
        boolean staleEpoch = request.epoch != currentEpoch;
        synchronized (this) {
            if (pending.get(request.command()) == request) {
                pending.remove(request.command());
            }
        }
        if (!shutdown && !staleEpoch) listener.onWriteResult(result);
    }

    private static long elapsed(long started) {
        return Math.max(0L, System.currentTimeMillis() - started);
    }
}