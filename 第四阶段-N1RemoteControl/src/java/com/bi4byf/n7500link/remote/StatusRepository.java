package com.bi4byf.n7500link.remote;

/** Thread-safe immutable snapshot store. Network threads only read copies. */
public final class StatusRepository {
    public interface Listener {
        void onSnapshotChanged(String instanceId, long revision, Long updatedAtMs, boolean stale);
    }

    public static final class View {
        public final String instanceId;
        public final long startedAtMs;
        public final long revision;
        public final String gatewayState;
        public final String gatewayLastError;
        public final String connectionState;
        public final String deviceLabel;
        public final Long connectedAtMs;
        public final String connectionLastError;
        public final RadioModels.RadioSnapshot radio;
        public final Long updatedAtMs;
        public final String source;
        public final boolean stale;

        private View(String instanceId, long startedAtMs, long revision,
                     String gatewayState, String gatewayLastError,
                     String connectionState, String deviceLabel, Long connectedAtMs,
                     String connectionLastError, RadioModels.RadioSnapshot radio,
                     Long updatedAtMs, String source, boolean stale) {
            this.instanceId = instanceId;
            this.startedAtMs = startedAtMs;
            this.revision = revision;
            this.gatewayState = gatewayState;
            this.gatewayLastError = gatewayLastError;
            this.connectionState = connectionState;
            this.deviceLabel = deviceLabel;
            this.connectedAtMs = connectedAtMs;
            this.connectionLastError = connectionLastError;
            this.radio = radio;
            this.updatedAtMs = updatedAtMs;
            this.source = source;
            this.stale = stale;
        }

        public boolean hasAnyData() { return radio.hasAnyData(); }
    }

    private final Object lock = new Object();
    private final String instanceId;
    private final long startedAtMs;
    private final Listener listener;
    private long revision;
    private String gatewayState = GatewayContract.GW_STARTING;
    private String gatewayLastError;
    private String connectionState = GatewayContract.CONN_DISCONNECTED;
    private String deviceLabel;
    private Long connectedAtMs;
    private String connectionLastError;
    private RadioModels.RadioSnapshot radio = RadioModels.RadioSnapshot.empty();
    private Long updatedAtMs;
    private String source = GatewayContract.SOURCE_NONE;

    public StatusRepository(Listener listener) {
        this.instanceId = GatewayContract.newInstanceId();
        this.startedAtMs = System.currentTimeMillis();
        this.listener = listener;
    }

    public String instanceId() { return instanceId; }

    public void markStarting() {
        synchronized (lock) {
            gatewayState = GatewayContract.GW_STARTING;
            connectionState = GatewayContract.CONN_DISCONNECTED;
            revision++;
        }
        notifyChanged();
    }

    public void markBluetoothConnecting(String label) {
        synchronized (lock) {
            gatewayState = GatewayContract.GW_CONNECTING_BLUETOOTH;
            connectionState = GatewayContract.CONN_CONNECTING;
            deviceLabel = safeLabel(label);
            connectionLastError = null;
            revision++;
        }
        notifyChanged();
    }

    public void markConnected(String label, long atMs) {
        synchronized (lock) {
            gatewayState = GatewayContract.GW_CONNECTING_BLUETOOTH;
            connectionState = GatewayContract.CONN_CONNECTED;
            deviceLabel = safeLabel(label);
            connectedAtMs = atMs;
            connectionLastError = null;
            revision++;
        }
        notifyChanged();
    }

    public void markReconnecting(String errorCode) {
        synchronized (lock) {
            gatewayState = GatewayContract.GW_RECONNECTING;
            connectionState = GatewayContract.CONN_RECONNECTING;
            connectedAtMs = null;
            connectionLastError = safeCode(errorCode);
            revision++;
        }
        notifyChanged();
    }

    public void markDegraded(String errorCode) {
        synchronized (lock) {
            gatewayState = GatewayContract.GW_DEGRADED;
            gatewayLastError = safeCode(errorCode);
            revision++;
        }
        notifyChanged();
    }

    public void markFault(String errorCode) {
        synchronized (lock) {
            gatewayState = GatewayContract.GW_FAULT;
            gatewayLastError = safeCode(errorCode);
            connectionState = GatewayContract.CONN_FAULT;
            connectedAtMs = null;
            revision++;
        }
        notifyChanged();
    }

    public void markStopped() {
        synchronized (lock) {
            gatewayState = GatewayContract.GW_STOPPED;
            connectionState = GatewayContract.CONN_DISCONNECTED;
            connectedAtMs = null;
            revision++;
        }
        notifyChanged();
    }

    public boolean apply(RadioParser.Parsed parsed, String updateSource, long nowMs) {
        if (parsed == null || !parsed.success) return false;
        boolean usable = false;
        synchronized (lock) {
            if (parsed.type == RadioParser.TYPE_DEVICE && parsed.device != null) {
                radio = radio.withDevice(parsed.device); usable = true;
            } else if (parsed.type == RadioParser.TYPE_SETTINGS && parsed.settings != null) {
                if (parsed.settingsBlock == null) return false; radio = radio.withSettings(parsed.settings, parsed.settingsBlock); usable = true;
            } else if (parsed.type == RadioParser.TYPE_STATUS && parsed.status != null) {
                radio = radio.withStatus(parsed.status); usable = true;
            } else if (parsed.type == RadioParser.TYPE_CHANNEL && parsed.channel != null) {
                radio = radio.withChannel(parsed.channel); usable = true;
            } else if (parsed.type == RadioParser.TYPE_VOLUME && parsed.volume != null) {
                radio = radio.withVolume(parsed.volume); usable = true;
            }
            if (usable) {
                updatedAtMs = nowMs;
                source = updateSource == null ? GatewayContract.SOURCE_POLL : updateSource;
                if (radio.hasAnyData()) {
                    gatewayState = GatewayContract.GW_ONLINE;
                    gatewayLastError = null;
                }
                revision++;
            }
        }
        if (usable) notifyChanged();
        return usable;
    }

    public View view(long nowMs) {
        synchronized (lock) {
            boolean stale = updatedAtMs == null
                    || !GatewayContract.CONN_CONNECTED.equals(connectionState)
                    || nowMs - updatedAtMs > GatewayContract.STALE_AFTER_MS;
            return new View(instanceId, startedAtMs, revision, gatewayState,
                    gatewayLastError, connectionState, deviceLabel, connectedAtMs,
                    connectionLastError, radio, updatedAtMs, source, stale);
        }
    }

    public boolean hasAnyData() {
        synchronized (lock) { return radio.hasAnyData(); }
    }

    /** Returns a defensive copy; raw protocol bytes are never exposed through HTTP. */
    public SettingsBlock currentSettingsBlock() {
        synchronized (lock) {
            if (radio.settingsBlock == null) return null;
            return SettingsBlock.fromPayload(radio.settingsBlock.baselinePayload());
        }
    }

    public String toJson(long nowMs) {
        View value = view(nowMs);
        StringBuilder out = new StringBuilder(1200);
        out.append('{')
                .append("\"schemaVersion\":").append(GatewayContract.SCHEMA_VERSION)
                .append(",\"ok\":true")
                .append(",\"instanceId\":").append(JsonUtil.quote(value.instanceId))
                .append(",\"revision\":").append(value.revision)
                .append(",\"generatedAt\":").append(JsonUtil.quote(GatewayContract.utc(nowMs)))
                .append(",\"updatedAt\":")
                .append(value.updatedAtMs == null ? "null" : JsonUtil.quote(GatewayContract.utc(value.updatedAtMs)))
                .append(",\"staleAfterMs\":").append(GatewayContract.STALE_AFTER_MS)
                .append(",\"stale\":").append(value.stale)
                .append(",\"source\":").append(JsonUtil.quote(value.source))
                .append(",\"gateway\":{")
                .append("\"state\":").append(JsonUtil.quote(value.gatewayState))
                .append(",\"version\":").append(JsonUtil.quote(GatewayContract.VERSION))
                .append(",\"startedAt\":").append(JsonUtil.quote(GatewayContract.utc(value.startedAtMs)))
                .append(",\"lastErrorCode\":").append(JsonUtil.nullableString(value.gatewayLastError))
                .append("},\"connection\":{")
                .append("\"state\":").append(JsonUtil.quote(value.connectionState))
                .append(",\"deviceLabel\":").append(JsonUtil.nullableString(value.deviceLabel))
                .append(",\"connectedAt\":")
                .append(value.connectedAtMs == null ? "null" : JsonUtil.quote(GatewayContract.utc(value.connectedAtMs)))
                .append(",\"lastError\":").append(JsonUtil.nullableString(value.connectionLastError))
                .append("},\"radio\":").append(value.radio.toRadioJson())
                .append(",\"volume\":").append(JsonUtil.nullableInt(value.radio.volume))
                .append(",\"channel\":").append(value.radio.toChannelJson())
                .append('}');
        return out.toString();
    }

    private void notifyChanged() {
        if (listener == null) return;
        long now = System.currentTimeMillis();
        View value = view(now);
        listener.onSnapshotChanged(value.instanceId, value.revision, value.updatedAtMs, value.stale);
    }

    private static String safeLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        if (trimmed.length() > 80) trimmed = trimmed.substring(0, 80);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safeCode(String code) {
        if (code == null) return null;
        String trimmed = code.trim();
        if (trimmed.length() > 80) trimmed = trimmed.substring(0, 80);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
