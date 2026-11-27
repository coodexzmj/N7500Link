package com.bi4byf.n7500link.gateway;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the one Bluetooth session, its epoch and the read-only polling plan. */
public final class RadioSession {
    private static final int[] RECONNECT_DELAYS_SECONDS = {1, 2, 4, 8, 16, 30, 30};

    private final BluetoothAdapter adapter;
    private final BluetoothDevice device;
    private final StatusRepository repository;
    private final GaiaCodec codec = new GaiaCodec();
    private final BluetoothLink link;
    private final CommandScheduler scheduler;
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    private final Object stateLock = new Object();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean notificationRefreshPending = new AtomicBoolean(false);

    private volatile boolean running;
    private volatile boolean connected;
    private volatile int sessionEpoch;
    private volatile int reconnectAttempts;
    private volatile int consecutiveReadFailures;
    private volatile long initialUntilMs;
    private volatile int currentChannelId = -1;
    private volatile int lastChannelRequestId = -1;
    private volatile long lastChannelRequestAtMs;

    public RadioSession(BluetoothAdapter adapter, BluetoothDevice device,
                        StatusRepository repository) {
        this.adapter = adapter;
        this.device = device;
        this.repository = repository;
        this.link = new BluetoothLink(adapter, new LinkListener());
        this.scheduler = new CommandScheduler(new SchedulerListener(), new SchedulerTransport());
    }

    public void start() {
        synchronized (stateLock) {
            if (running) return;
            running = true;
        }
        control.execute(() -> {
            repository.markStarting();
            schedulePolling();
            connectAttempt();
        });
    }

    public void stop() {
        synchronized (stateLock) {
            if (!running) return;
            running = false;
            connected = false;
            sessionEpoch++;
            scheduler.startEpoch(sessionEpoch);
        }
        reconnectScheduled.set(false);
        timers.shutdownNow();
        scheduler.shutdown();
        link.shutdown();
        control.shutdownNow();
        repository.markStopped();
    }

    public boolean isConnected() { return connected; }
    public boolean isRunning() { return running; }
    public int sessionEpoch() { return sessionEpoch; }
    public long completedReads() { return scheduler.completedCount(); }
    public long timeoutReads() { return scheduler.timeoutCount(); }
    public long mergedReads() { return scheduler.mergedCount(); }
    public String diagnosticCounters() {
        return "BT分段=" + link.getRxChunks()
                + " 字节=" + link.getRxBytes()
                + " GAIA帧=" + codec.getRxFrames()
                + " 丢弃字节=" + codec.getDiscardedBytes()
                + " TX=" + scheduler.sentCount()
                + " 匹配=" + scheduler.matchedCount()
                + " 超时=" + scheduler.timeoutCount()
                + " 合并=" + scheduler.mergedCount();
    }

    private void schedulePolling() {
        timers.scheduleWithFixedDelay(this::pollStatus, 5, 5, TimeUnit.SECONDS);
        timers.scheduleWithFixedDelay(this::pollSettingsAndVolume, 30, 30, TimeUnit.SECONDS);
        timers.scheduleWithFixedDelay(this::pollChannel, 60, 60, TimeUnit.SECONDS);
    }

    private void pollStatus() {
        if (running && connected) {
            scheduler.requestStatus();
        }
    }

    private void pollSettingsAndVolume() {
        if (running && connected) {
            scheduler.requestSettings();
            scheduler.requestVolume();
            scheduler.requestDeviceInfo();
        }
    }

    private void pollChannel() {
        if (!running || !connected) return;
        int channelId = currentChannelId;
        if (channelId >= 0) scheduler.requestChannel(channelId);
    }

    private void connectAttempt() {
        int epoch;
        synchronized (stateLock) {
            reconnectScheduled.set(false);
            if (!running) return;
            sessionEpoch++;
            epoch = sessionEpoch;
            scheduler.startEpoch(epoch);
            codec.reset();
        }
        repository.markBluetoothConnecting(safeDeviceLabel());
        if (adapter == null || !adapter.isEnabled()) {
            handleConnectionFailure(epoch, "BLUETOOTH_ADAPTER_DISABLED");
            return;
        }
        link.connect(device, epoch);
    }

    private void handleConnected(int epoch, String label) {
        synchronized (stateLock) {
            if (!running || epoch != sessionEpoch) return;
            connected = true;
            reconnectAttempts = 0;
            consecutiveReadFailures = 0;
            initialUntilMs = System.currentTimeMillis() + 10000L;
            currentChannelId = -1;
            lastChannelRequestId = -1;
            lastChannelRequestAtMs = 0L;
        }
        repository.markConnected(label == null ? "N7500" : label, System.currentTimeMillis());
        // 与已验证的 N1Control 顺序一致：先完成普通读取，再注册单向事件通知。
        scheduler.requestDeviceInfo();
        scheduler.requestSettings();
        scheduler.requestStatus();
        scheduler.requestVolume();
        scheduler.requestNotificationRegistration();
    }

    private void handleConnectionFailure(int epoch, String code) {
        synchronized (stateLock) {
            if (!running || epoch != sessionEpoch || connected) return;
            connected = false;
        }
        repository.markReconnecting(code);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running || !reconnectScheduled.compareAndSet(false, true)) return;
        int index = Math.min(reconnectAttempts, RECONNECT_DELAYS_SECONDS.length - 1);
        int delay = RECONNECT_DELAYS_SECONDS[index];
        reconnectAttempts++;
        try {
            timers.schedule(() -> {
                if (!running) {
                    reconnectScheduled.set(false);
                    return;
                }
                connectAttempt();
            }, delay, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            reconnectScheduled.set(false);
        }
    }

    private void handleLinkLoss(int epoch, String code) {
        if (!running || epoch != sessionEpoch) return;
        int newEpoch;
        synchronized (stateLock) {
            if (!running || epoch != sessionEpoch) return;
            connected = false;
            sessionEpoch++;
            newEpoch = sessionEpoch;
            scheduler.startEpoch(newEpoch);
            codec.reset();
        }
        link.disconnect(newEpoch);
        repository.markReconnecting(code);
        scheduleReconnect();
    }

    private void processReadResult(CommandScheduler.ReadResult result) {
        if (!running || !connected || result == null || result.epoch != sessionEpoch
                || result.stale) return;
        // 注册通知由 EVENT_NOTIFICATION 确认，没有普通命令响应。
        if (!result.kind.expectsResponse) return;
        if (result.parsed == null || !result.parsed.success) {
            consecutiveReadFailures++;
            if (consecutiveReadFailures >= 3) {
                if (result.timedOut) {
                    // 半开 RFCOMM 链路仍会显示 connected，必须主动重建。
                    handleLinkLoss(result.epoch, "RADIO_READ_TIMEOUT");
                } else {
                    repository.markDegraded("RADIO_READ_FAILED");
                }
            }
            return;
        }
        consecutiveReadFailures = 0;
        String source = System.currentTimeMillis() < initialUntilMs
                ? GatewayContract.SOURCE_INITIAL : GatewayContract.SOURCE_POLL;
        if (result.parsed.type != RadioParser.TYPE_OTHER) {
            repository.apply(result.parsed, source, System.currentTimeMillis());
        }

        int candidateChannel = currentChannelId;
        if (result.parsed.type == RadioParser.TYPE_STATUS && result.parsed.status != null
                && result.parsed.status.currentChannel != null) {
            candidateChannel = result.parsed.status.currentChannel;
        } else if (result.parsed.type == RadioParser.TYPE_SETTINGS
                && result.parsed.settings != null) {
            if ("A".equals(result.parsed.settings.currentSide)
                    && result.parsed.settings.channelA != null) {
                candidateChannel = result.parsed.settings.channelA;
            } else if ("B".equals(result.parsed.settings.currentSide)
                    && result.parsed.settings.channelB != null) {
                candidateChannel = result.parsed.settings.channelB;
            }
        }
        currentChannelId = candidateChannel;
        if (result.parsed.type != RadioParser.TYPE_CHANNEL && candidateChannel >= 0) {
            requestChannelIfNeeded(candidateChannel, result.epoch);
        }
    }

    private void requestChannelIfNeeded(int channelId, int epoch) {
        long now = System.currentTimeMillis();
        if (channelId != lastChannelRequestId
                || lastChannelRequestAtMs == 0L
                || now - lastChannelRequestAtMs >= 60000L) {
            lastChannelRequestId = channelId;
            lastChannelRequestAtMs = now;
            scheduler.requestChannel(channelId);
        }
    }

    private void processFrame(int epoch, byte[] body) {
        if (!running || !connected || epoch != sessionEpoch || body == null) return;
        if (RadioParser.isNotification(body)) {
            RadioParser.Parsed parsed = RadioParser.parseNotification(body);
            if (parsed.success && (parsed.type == RadioParser.TYPE_STATUS
                    || parsed.type == RadioParser.TYPE_SETTINGS)) {
                repository.apply(parsed, GatewayContract.SOURCE_NOTIFICATION,
                        System.currentTimeMillis());
                if (parsed.type == RadioParser.TYPE_STATUS) {
                    scheduleStatusAfterNotification(epoch);
                }
            }
            return;
        }
        scheduler.onResponse(epoch, body);
    }

    private void scheduleStatusAfterNotification(int epoch) {
        if (!notificationRefreshPending.compareAndSet(false, true)) return;
        try {
            timers.schedule(() -> {
                notificationRefreshPending.set(false);
                if (running && connected && epoch == sessionEpoch) scheduler.requestStatus();
            }, 250, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            notificationRefreshPending.set(false);
        }
    }

    private String safeDeviceLabel() {
        if (device == null) return "N7500";
        try {
            String name = device.getName();
            if (name == null || name.trim().isEmpty()) return "N7500";
            String value = name.trim();
            return value.length() > 48 ? value.substring(0, 48) : value;
        } catch (RuntimeException e) {
            return "N7500";
        }
    }

    private void postControl(Runnable action) {
        if (!running) return;
        try {
            control.execute(() -> {
                if (running) action.run();
            });
        } catch (RuntimeException ignored) {}
    }

    private final class LinkListener implements BluetoothLink.Listener {
        @Override public void onConnected(final int epoch, final String label) {
            postControl(() -> handleConnected(epoch, label));
        }
        @Override public void onConnectFailed(final int epoch, final String code) {
            postControl(() -> handleConnectionFailure(epoch, code));
        }
        @Override public void onDisconnected(final int epoch, final String code) {
            postControl(() -> handleLinkLoss(epoch, code));
        }
        @Override public void onData(final int epoch, byte[] data) {
            if (!running || !connected || epoch != sessionEpoch) return;
            codec.feed(data, new GaiaCodec.Listener() {
                @Override public void onFrame(byte[] body) { processFrame(epoch, body); }
                @Override public void onDiscarded(int byteCount) {}
            });
        }
    }

    private final class SchedulerListener implements CommandScheduler.Listener {
        @Override public void onReadResult(final CommandScheduler.ReadResult result) {
            postControl(() -> processReadResult(result));
        }
        @Override public void onTransportFault(final int epoch, final String code) {
            postControl(() -> handleLinkLoss(epoch, code));
        }
    }

    private final class SchedulerTransport implements CommandScheduler.Transport {
        @Override public boolean isReady(int epoch) { return link.isReady(epoch); }
        @Override public void send(int epoch, byte[] frame) throws IOException {
            link.sendFrame(epoch, frame);
        }
    }
}
