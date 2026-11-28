package com.bi4byf.n7500link.direct;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the dedicated N7500 audio RFCOMM socket and stream decoder.
 *
 * The control link remains independent: an audio connect or decode failure
 * only updates the local room status and can never change radio controls.
 */
final class AudioLink {
    private static final UUID AUDIO_UUID =
            UUID.fromString("00004315-32FA-40DB-85ED-FBFEBA2D86E6");
    private static final long MIN_RETRY_DELAY_MS = 2000L;
    private static final long MAX_RETRY_DELAY_MS = 30000L;
    private static final long HANDSHAKE_INTERVAL_MS = 30L;

    private final BluetoothAdapter adapter;
    private final AudioRoomService roomService;
    private final RadioAudioStreamParser parser = new RadioAudioStreamParser();
    private final Object lifecycleLock = new Object();

    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile int desiredEpoch;
    private volatile BluetoothSocket socket;
    private volatile BluetoothSocket connectingSocket;
    private volatile long rxBytes;
    private volatile long rxChunks;
    private volatile long pcmBytes;
    private volatile long packets;
    private volatile long audioPackets;
    private volatile long ignoredPackets;
    private volatile long decodedFrames;
    private volatile long rejectedFrames;
    private volatile long escapeErrors;
    private volatile long oversizedPackets;
    private volatile long outputSamples;
    private volatile long handshakeWrites;
    private volatile long lastChunkAtMs;
    private volatile long lastHandshakeAtMs;
    private volatile long lastPcmAtMs;

    AudioLink(BluetoothAdapter adapter, AudioRoomService roomService) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        if (roomService == null) throw new IllegalArgumentException("roomService");
        this.adapter = adapter;
        this.roomService = roomService;
    }

    void start(BluetoothDevice device) {
        if (device == null) throw new IllegalArgumentException("device");
        int epoch;
        synchronized (lifecycleLock) {
            if (running) return;
            if (worker == null || worker.isShutdown()) {
                worker = Executors.newSingleThreadExecutor();
            }
            running = true;
            epoch = ++desiredEpoch;
        }
        roomService.markLinkState("CONNECTING", null);
        try {
            worker.execute(() -> runLoop(device, epoch));
        } catch (RuntimeException e) {
            synchronized (lifecycleLock) {
                running = false;
                desiredEpoch++;
            }
            roomService.markLinkState("DISCONNECTED", "AUDIO_THREAD_START_FAILED");
        }
    }

    void stop() {
        BluetoothSocket active;
        BluetoothSocket connecting;
        synchronized (lifecycleLock) {
            if (!running) return;
            running = false;
            desiredEpoch++;
            active = socket;
            connecting = connectingSocket;
            socket = null;
            connectingSocket = null;
            worker.shutdownNow();
        }
        closeQuietly(connecting);
        closeQuietly(active);
        roomService.markLinkState("DISCONNECTED", null);
        parser.reset();
    }

    boolean isRunning() {
        BluetoothSocket active = socket;
        return running && active != null && active.isConnected();
    }

    int epoch() { return desiredEpoch; }

    String diagnosticCounters() {
        return "AUDIO epoch=" + desiredEpoch
                + " chunks=" + rxChunks
                + " bytes=" + rxBytes
                + " pcmBytes=" + pcmBytes
                + " packets=" + packets
                + " audioPackets=" + audioPackets
                + " ignored=" + ignoredPackets
                + " decoded=" + decodedFrames
                + " rejected=" + rejectedFrames
                + " escapeErrors=" + escapeErrors
                + " oversized=" + oversizedPackets
                + " handshakeWrites=" + handshakeWrites;
    }

    String diagnosticsJson() {
        long now = System.currentTimeMillis();
        return "{\"ok\":true"
                + ",\"state\":" + JsonUtil.quote(roomService.audioState())
                + ",\"lastError\":" + JsonUtil.nullableString(roomService.lastErrorCode())
                + ",\"lastPcmAt\":"
                + (lastPcmAtMs <= 0L ? "null"
                        : JsonUtil.quote(GatewayContract.utc(lastPcmAtMs)))
                + ",\"lastChunkAgeMs\":"
                + (lastChunkAtMs <= 0L ? "null" : Long.toString(now - lastChunkAtMs))
                + ",\"counters\":{"
                + "\"rxChunks\":" + rxChunks
                + ",\"rxBytes\":" + rxBytes
                + ",\"pcmBytes\":" + pcmBytes
                + ",\"packets\":" + packets
                + ",\"audioPackets\":" + audioPackets
                + ",\"ignoredPackets\":" + ignoredPackets
                + ",\"escapeErrors\":" + escapeErrors
                + ",\"oversizedPackets\":" + oversizedPackets
                + ",\"decodedFrames\":" + decodedFrames
                + ",\"rejectedFrames\":" + rejectedFrames
                + ",\"outputSamples\":" + outputSamples
                + ",\"handshakeWrites\":" + handshakeWrites
                + "}}";
    }

    private void runLoop(BluetoothDevice device, int epoch) {
        long retryDelay = MIN_RETRY_DELAY_MS;
        while (isCurrent(epoch)) {
            if (!isBonded(device)) {
                roomService.markLinkState("RECONNECTING", "AUDIO_DEVICE_NOT_PAIRED");
                if (!waitBeforeRetry(epoch, retryDelay)) break;
                retryDelay = nextDelay(retryDelay);
                continue;
            }
            try {
                if (adapter.isDiscovering()) adapter.cancelDiscovery();
            } catch (RuntimeException ignored) {}

            BluetoothSocket opened = openSocket(device, epoch);
            if (!isCurrent(epoch)) {
                closeQuietly(opened);
                break;
            }
            if (opened == null) {
                roomService.markLinkState("RECONNECTING", "AUDIO_CONNECT_FAILED");
                if (!waitBeforeRetry(epoch, retryDelay)) break;
                retryDelay = nextDelay(retryDelay);
                continue;
            }

            socket = opened;
            parser.reset();
            lastPcmAtMs = 0L;
            lastChunkAtMs = 0L;
            lastHandshakeAtMs = 0L;
            roomService.markLinkState("WAITING_PCM", null);
            String failure = receiveLoop(opened, epoch);
            closeQuietly(opened);
            if (socket == opened) socket = null;
            parser.reset();
            if (!isCurrent(epoch)) break;
            roomService.markLinkState("RECONNECTING", failure);
            if (!waitBeforeRetry(epoch, retryDelay)) break;
            retryDelay = nextDelay(retryDelay);
        }
    }

    private String receiveLoop(BluetoothSocket active, int epoch) {
        InputStream input = null;
        OutputStream output = null;
        byte[] chunk = new byte[2048];
        try {
            setAudioPriority();
            input = active.getInputStream();
            // The original HT audio client obtains both directions and sends
            // a one-byte 0x01 credit/handshake when its outgoing queue is empty.
            // Without this write the N7500 socket can stay connected while
            // never delivering command-9 SBC payloads.
            output = active.getOutputStream();
            while (isCurrent(epoch) && active == socket && active.isConnected()
                    && !Thread.currentThread().isInterrupted()) {
                int count = input.read(chunk);
                if (count < 0) return "AUDIO_STREAM_CLOSED";
                if (count == 0) continue;
                long now = System.currentTimeMillis();
                rxChunks++;
                rxBytes += count;
                lastChunkAtMs = now;
                if (now - lastHandshakeAtMs >= HANDSHAKE_INTERVAL_MS) {
                    output.write(0x01);
                    output.flush();
                    handshakeWrites++;
                    lastHandshakeAtMs = now;
                }
                byte[] pcm = parser.feed(chunk, count);
                refreshParserCounters();
                if (pcm.length > 0) {
                    pcmBytes += pcm.length;
                    lastPcmAtMs = now;
                    roomService.appendPcm(pcm);
                }
            }
            return "AUDIO_LINK_STOPPED";
        } catch (IOException e) {
            return "AUDIO_RECEIVE_INTERRUPTED";
        } catch (RuntimeException e) {
            return "AUDIO_STREAM_INVALID";
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
    }

    private static void setAudioPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (RuntimeException ignored) {}
        try {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        } catch (RuntimeException ignored) {}
    }

    private void refreshParserCounters() {
        packets = parser.packets();
        audioPackets = parser.audioPackets();
        ignoredPackets = parser.ignoredPackets();
        decodedFrames = parser.decodedFrames();
        rejectedFrames = parser.rejectedFrames();
        escapeErrors = parser.escapeErrors();
        oversizedPackets = parser.oversizedPackets();
        outputSamples = parser.outputSamples();
    }

    private BluetoothSocket openSocket(BluetoothDevice device, int epoch) {
        BluetoothSocket opened = null;
        try {
            BluetoothSocket direct = device.createRfcommSocketToServiceRecord(AUDIO_UUID);
            connectingSocket = direct;
            direct.connect();
            connectingSocket = null;
            opened = direct;
        } catch (Exception firstError) {
            connectingSocket = null;
            closeQuietly(opened);
        }
        if (opened != null || !isCurrent(epoch)) return opened;
        // Do not probe RFCOMM channels 1..12. That can connect to the control
        // or another SDP service and mislabel it as an audio stream.
        return null;
    }

    private boolean isBonded(BluetoothDevice device) {
        try {
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isCurrent(int epoch) {
        return running && epoch == desiredEpoch && !Thread.currentThread().isInterrupted();
    }

    private boolean waitBeforeRetry(int epoch, long delayMs) {
        long deadline = System.currentTimeMillis() + delayMs;
        while (isCurrent(epoch)) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) return true;
            try {
                Thread.sleep(Math.min(remaining, 500L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static long nextDelay(long current) {
        long doubled = current >= MAX_RETRY_DELAY_MS / 2L ? MAX_RETRY_DELAY_MS : current * 2L;
        return Math.min(doubled, MAX_RETRY_DELAY_MS);
    }

    private static void closeQuietly(BluetoothSocket target) {
        if (target == null) return;
        try { target.close(); } catch (Exception ignored) {}
    }

    private static void closeQuietly(java.io.Closeable target) {
        if (target == null) return;
        try { target.close(); } catch (IOException ignored) {}
    }
}
