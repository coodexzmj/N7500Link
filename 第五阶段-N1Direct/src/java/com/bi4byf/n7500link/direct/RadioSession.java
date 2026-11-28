package com.bi4byf.n7500link.direct;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the one Bluetooth session, its epoch and the bounded low-risk control state machine. */
public final class RadioSession {
    private static final int[] RECONNECT_DELAYS_SECONDS = {1, 2, 4, 8, 16, 30, 30};

    private final BluetoothAdapter adapter;
    private final BluetoothDevice device;
    private final StatusRepository repository;
    private final GaiaCodec codec = new GaiaCodec();
    private final BluetoothLink link;
    private final CommandScheduler scheduler;
    private final ExecutorService controlWorker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    private final LowRiskWriteController controller;
    private final ChannelCatalogService channelCatalog;
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

    /** Optional lifecycle hook for components that must follow the control link. */
    public interface Listener {
        void onControlReady(int epoch);
        void onControlLost(int epoch, String reasonCode);
    }

    private final Listener listener;

    public RadioSession(BluetoothAdapter adapter, BluetoothDevice device,
                        StatusRepository repository) {
        this(adapter, device, repository, null);
    }

    public RadioSession(BluetoothAdapter adapter, BluetoothDevice device,
                        StatusRepository repository, Listener listener) {
        this.adapter = adapter;
        this.device = device;
        this.repository = repository;
        this.listener = listener;
        this.link = new BluetoothLink(adapter, new LinkListener());
        this.scheduler = new CommandScheduler(new SchedulerListener(), new SchedulerTransport());
        this.controller = new LowRiskWriteController(
                new ControlPort(), controlWorker, timers);
        this.channelCatalog = new ChannelCatalogService(
                new CatalogPort(), System::currentTimeMillis, this::scheduleCatalogPump);
    }

    public void start() {
        synchronized (stateLock) {
            if (running) return;
            running = true;
        }
        controlWorker.execute(() -> {
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
            channelCatalog.beginEpoch(sessionEpoch);
            channelCatalog.markOffline("SERVICE_STOPPED");
        }
        reconnectScheduled.set(false);
        if (listener != null) {
            try { listener.onControlLost(sessionEpoch, "SERVICE_STOPPED"); }
            catch (RuntimeException ignored) {}
        }
        controller.cancel(LowRiskWriteController.CANCELLED, "SERVICE_STOPPED");
        timers.shutdownNow();
        scheduler.shutdown();
        link.shutdown();
        controlWorker.shutdownNow();
        repository.markStopped();
    }

    public boolean isConnected() { return connected; }
    public boolean isRunning() { return running; }
    public int sessionEpoch() { return sessionEpoch; }
    LowRiskWriteController controlController() { return controller; }
    public ChannelCatalogService channelCatalog() { return channelCatalog; }
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
        if (running && connected && !controller.isBusy()) {
            scheduler.requestStatus();
        }
    }

    private void pollSettingsAndVolume() {
        if (running && connected && !controller.isBusy()) {
            scheduler.requestSettings();
            scheduler.requestVolume();
            scheduler.requestDeviceInfo();
        }
    }

    private void pollChannel() {
        if (!running || !connected || controller.isBusy()
                || channelCatalog.isRefreshing()) return;
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
            channelCatalog.beginEpoch(epoch);
            channelCatalog.markOffline("BLUETOOTH_CONNECTING");
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
        if (listener != null) {
            try { listener.onControlReady(epoch); }
            catch (RuntimeException ignored) {}
        }
    }

    private void handleConnectionFailure(int epoch, String code) {
        synchronized (stateLock) {
            if (!running || epoch != sessionEpoch || connected) return;
            connected = false;
        }
        channelCatalog.markOffline("CONNECT_FAILED_" + safeReasonCode(code));
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
            channelCatalog.beginEpoch(newEpoch);
            channelCatalog.markOffline("LINK_LOST_" + safeReasonCode(code));
            codec.reset();
        }
        controller.cancel(LowRiskWriteController.LINK_LOST,
                "LINK_LOST_" + safeReasonCode(code));
        link.disconnect(newEpoch);
        if (listener != null) {
            try { listener.onControlLost(epoch, safeReasonCode(code)); }
            catch (RuntimeException ignored) {}
        }
        repository.markReconnecting(code);
        scheduleReconnect();
    }

    private void processReadResult(CommandScheduler.ReadResult result) {
        if (result != null && result.source == CommandScheduler.ReadSource.CATALOG) {
            channelCatalog.onReadResult(result);
            return;
        }
        if (!running || !connected || result == null || result.epoch != sessionEpoch
                || result.stale) return;
        if (result != null) controller.onReadResult(result);
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
        if (controller.isBusy() || channelCatalog.isRefreshing()) return;
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
                if (running && connected && epoch == sessionEpoch
                        && !controller.isBusy()) scheduler.requestStatus();
            }, 250, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            notificationRefreshPending.set(false);
        }
    }

    private static String safeReasonCode(String value) {
        if (value == null || value.trim().isEmpty()) return "UNKNOWN";
        String result = value.trim();
        return result.length() > 48 ? result.substring(0, 48) : result;
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
            controlWorker.execute(() -> {
                if (running) action.run();
            });
        } catch (RuntimeException ignored) {}
    }

    private void scheduleCatalogPump(Runnable task, long delayMs) {
        timers.schedule(task, delayMs, TimeUnit.MILLISECONDS);
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
        @Override public void onWriteResult(final CommandScheduler.WriteResult result) {
            postControl(() -> controller.onWriteResult(result));
        }
        @Override public void onTransportFault(final int epoch, final String code) {
            postControl(() -> handleLinkLoss(epoch, code));
        }
    }

    private final class ControlPort implements LowRiskWriteController.Port {
        @Override public boolean isControlReady() {
            return running && connected && link.isReady(sessionEpoch);
        }
        @Override public int epoch() { return sessionEpoch; }
        @Override public boolean requestSettingsRead() { return scheduler.requestSettings(); }
        @Override public boolean requestVolumeRead() { return scheduler.requestVolume(); }
        @Override public boolean requestVolumeWrite(int volume) {
            return scheduler.requestVolumeWrite(volume);
        }
        @Override public boolean requestSettingsWrite(byte[] completePayload) {
            return scheduler.requestSettingsWrite(completePayload);
        }
        @Override public boolean hasPendingRead(boolean volume) {
            return scheduler.hasPendingCommand(volume
                    ? GaiaCodec.CMD_GET_VOLUME : GaiaCodec.CMD_READ_SETTINGS);
        }
    }
    private final class CatalogPort implements ChannelCatalogService.Port {
        @Override public int epoch() { return sessionEpoch; }
        @Override public boolean isReady(int epoch) {
            return running && connected && link.isReady(epoch);
        }
        @Override public boolean isControlBusy() { return controller.isBusy(); }
        @Override public boolean requestCatalogChannel(int channelId, long requestId) {
            return scheduler.requestCatalogChannel(channelId, requestId);
        }
    }
    private final class SchedulerTransport implements CommandScheduler.Transport {
        @Override public boolean isReady(int epoch) { return link.isReady(epoch); }
        @Override public void send(int epoch, byte[] frame) throws IOException {
            link.sendFrame(epoch, frame);
        }
    }
}
