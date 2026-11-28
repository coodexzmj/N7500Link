package com.bi4byf.n7500link.direct;

/**
 * Fixed 0..31 channel catalog state machine. It never accepts payloads, raw
 * protocol data or write commands; every radio access is a named channel read.
 */
public final class ChannelCatalogService {
    public static final int CHANNEL_COUNT = 32;
    public static final String STATE_EMPTY = "EMPTY";
    public static final String STATE_REFRESHING = "REFRESHING";
    public static final String STATE_COMPLETE = "COMPLETE";
    public static final String STATE_PARTIAL = "PARTIAL";
    public static final String STATE_OFFLINE = "OFFLINE";

    public static final String CHANNEL_PENDING = "PENDING";
    public static final String CHANNEL_OK = "OK";
    public static final String CHANNEL_TIMEOUT = "TIMEOUT";
    public static final String CHANNEL_ERROR = "ERROR";

    private static final long RETRY_DELAY_MS = 250L;

    public interface Port {
        int epoch();
        boolean isReady(int epoch);
        boolean isControlBusy();
        boolean requestCatalogChannel(int channelId, long requestId);
    }

    public interface Clock {
        long nowMs();
    }

    public interface PumpScheduler {
        void schedule(Runnable task, long delayMs);
    }

    public static final class Entry {
        public final int id;
        public String name;
        public Integer rxFreqHz;
        public Integer txFreqHz;
        public String status = CHANNEL_PENDING;
        public boolean stale;
        public long readAtMs;
        public long attemptedAtMs;
        public String errorCode;

        Entry(int id) { this.id = id; }
    }

    private final Port port;
    private final Clock clock;
    private final PumpScheduler pumpScheduler;
    private final Entry[] channels = new Entry[CHANNEL_COUNT];

    private int epoch;
    private int generation;
    private int autoStartedEpoch;
    private String state = STATE_EMPTY;
    private String lastErrorCode;
    private long lastRefreshAtMs;
    private boolean inFlight;
    private int inFlightChannel = -1;
    private long inFlightRequestId;
    private long nextRequestId;
    private boolean retryScheduled;

    public ChannelCatalogService(Port port, Clock clock, PumpScheduler pumpScheduler) {
        if (port == null || clock == null || pumpScheduler == null) {
            throw new IllegalArgumentException("catalog dependencies are required");
        }
        this.port = port;
        this.clock = clock;
        this.pumpScheduler = pumpScheduler;
        for (int i = 0; i < CHANNEL_COUNT; i++) channels[i] = new Entry(i);
    }

    /** Clears all values when the Bluetooth session epoch changes. */
    public synchronized void beginEpoch(int newEpoch) {
        if (newEpoch <= 0) throw new IllegalArgumentException("epoch must be positive");
        epoch = newEpoch;
        generation = 0;
        autoStartedEpoch = 0;
        state = STATE_EMPTY;
        lastErrorCode = null;
        lastRefreshAtMs = 0L;
        inFlight = false;
        inFlightChannel = -1;
        inFlightRequestId = 0L;
        nextRequestId = 0L;
        retryScheduled = false;
        for (Entry entry : channels) {
            entry.name = null;
            entry.rxFreqHz = null;
            entry.txFreqHz = null;
            entry.status = CHANNEL_PENDING;
            entry.stale = false;
            entry.readAtMs = 0L;
            entry.attemptedAtMs = 0L;
            entry.errorCode = null;
        }
    }

    public synchronized void markOffline(String errorCode) {
        state = STATE_OFFLINE;
        lastErrorCode = safeErrorCode(errorCode);
        inFlight = false;
        inFlightChannel = -1;
        inFlightRequestId = 0L;
        retryScheduled = false;
    }

    /** First catalog GET in an epoch starts at most one automatic refresh. */
    public synchronized boolean autoTriggerRefresh() {
        if (STATE_REFRESHING.equals(state)) return true;
        int currentEpoch = port.epoch();
        if (currentEpoch <= 0 || currentEpoch != epoch || autoStartedEpoch == epoch) {
            return false;
        }
        if (!port.isReady(epoch)) {
            state = STATE_OFFLINE;
            lastErrorCode = "RADIO_NOT_READY";
            return false;
        }
        boolean started = startRefreshLocked();
        if (started) autoStartedEpoch = epoch;
        return started;
    }

    /** Manual refresh is idempotent while a generation is already running. */
    public synchronized boolean requestRefresh() {
        if (state.equals(STATE_REFRESHING)) return true;
        if (port.epoch() != epoch || !port.isReady(epoch)) {
            state = STATE_OFFLINE;
            lastErrorCode = "RADIO_NOT_READY";
            return false;
        }
        return startRefreshLocked();
    }

    private boolean startRefreshLocked() {
        if (epoch <= 0) {
            state = STATE_OFFLINE;
            lastErrorCode = "SESSION_NOT_READY";
            return false;
        }
        generation++;
        state = STATE_REFRESHING;
        lastErrorCode = null;
        inFlight = false;
        inFlightChannel = -1;
        inFlightRequestId = 0L;
        for (Entry entry : channels) {
            entry.status = CHANNEL_PENDING;
            entry.stale = entry.readAtMs > 0L;
            entry.attemptedAtMs = 0L;
            entry.errorCode = null;
        }
        return schedulePumpLocked(0L);
    }

    /** Advances the generation by at most one outstanding read. */
    public void pump() {
        synchronized (this) {
            if (!state.equals(STATE_REFRESHING) || inFlight) return;
            if (port.epoch() != epoch || !port.isReady(epoch)) {
                state = STATE_OFFLINE;
                lastErrorCode = "RADIO_NOT_READY";
                return;
            }
            if (port.isControlBusy()) {
                schedulePumpLocked(RETRY_DELAY_MS);
                return;
            }
            int next = nextPendingChannel();
            if (next < 0) {
                finishGenerationLocked();
                return;
            }
            long requestId = ++nextRequestId;
            boolean queued = false;
            try {
                queued = port.requestCatalogChannel(next, requestId);
            } catch (RuntimeException ignored) {
                lastErrorCode = "CATALOG_REQUEST_FAILED";
            }
            if (!queued) {
                schedulePumpLocked(RETRY_DELAY_MS);
                return;
            }
            inFlight = true;
            inFlightChannel = next;
            inFlightRequestId = requestId;
        }
    }

    /** Dispatches only a catalog-tagged scheduler result to this state machine. */
    public void onReadResult(CommandScheduler.ReadResult result) {
        synchronized (this) {
            if (result == null || result.source != CommandScheduler.ReadSource.CATALOG) return;
            if (result.epoch != epoch || !inFlight
                    || result.requestId != inFlightRequestId
                    || result.targetChannelId != inFlightChannel) {
                return;
            }
            long now = clock.nowMs();
            Entry entry = channels[inFlightChannel];
            inFlight = false;
            int expected = inFlightChannel;
            inFlightChannel = -1;
            inFlightRequestId = 0L;
            entry.attemptedAtMs = now;

            if (result.stale) {
                markFailureLocked(entry, CHANNEL_ERROR, "STALE_RESPONSE", now);
            } else if (result.timedOut) {
                markFailureLocked(entry, CHANNEL_TIMEOUT, "CHANNEL_RESPONSE_TIMEOUT", now);
            } else if (result.parsed == null || !result.parsed.success) {
                markFailureLocked(entry, CHANNEL_ERROR,
                        result.errorCode != null ? result.errorCode
                                : (result.parsed == null ? "CHANNEL_PARSE_FAILED"
                                : result.parsed.errorCode), now);
            } else if (result.parsed.channel == null) {
                markFailureLocked(entry, CHANNEL_ERROR, "CHANNEL_DATA_MISSING", now);
            } else if (result.parsed.channel.id != expected) {
                markFailureLocked(entry, CHANNEL_ERROR, "CHANNEL_ID_MISMATCH", now);
            } else {
                Range range = validRange(result.parsed.channel.rxFreqHz,
                        result.parsed.channel.txFreqHz);
                if (!range.valid) {
                    markFailureLocked(entry, CHANNEL_ERROR, "CHANNEL_FREQUENCY_INVALID", now);
                } else {
                    // 0/0 denotes a successful empty channel. Do not expose it as
                    // a valid 0.000000 MHz frequency or convert it a second time.
                    boolean empty = range.empty;
                    entry.name = empty ? null : result.parsed.channel.name;
                    entry.rxFreqHz = empty ? null : result.parsed.channel.rxFreqHz;
                    entry.txFreqHz = empty ? null : result.parsed.channel.txFreqHz;
                    entry.status = CHANNEL_OK;
                    entry.stale = false;
                    entry.readAtMs = now;
                    entry.errorCode = null;
                }
            }

            if (nextPendingChannel() < 0) {
                finishGenerationLocked();
            } else {
                schedulePumpLocked(0L);
            }
        }
    }

    public synchronized boolean isRefreshing() {
        return state.equals(STATE_REFRESHING);
    }

    /** True only while the captured session epoch still owns a ready radio link. */
    public synchronized boolean isRadioReady() {
        return epoch > 0 && port.epoch() == epoch && port.isReady(epoch);
    }

    public synchronized String state() { return state; }
    public synchronized int epoch() { return epoch; }
    public synchronized int generation() { return generation; }

    public synchronized String toJson() {
        long now = clock.nowMs();
        int success = successCountLocked();
        int completed = completedCountLocked();
        boolean stale = anyStaleLocked();
        StringBuilder out = new StringBuilder(32 * 180);
        out.append("{\"state\":").append(JsonUtil.quote(state));
        out.append(",\"epoch\":").append(epoch);
        out.append(",\"generation\":").append(generation);
        out.append(",\"total\":").append(CHANNEL_COUNT);
        out.append(",\"completedCount\":").append(completed);
        out.append(",\"successCount\":").append(success);
        out.append(",\"stale\":").append(stale);
        out.append(",\"lastRefreshAtMs\":").append(nullableTime(lastRefreshAtMs));
        out.append(",\"lastErrorCode\":").append(JsonUtil.nullableString(lastErrorCode));
        out.append(",\"observedAtMs\":").append(now);
        out.append(",\"channels\":[");
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            if (i > 0) out.append(',');
            appendEntryJson(out, channels[i]);
        }
        return out.append("]}").toString();
    }

    public synchronized String channelJson(int id) {
        if (id < 0 || id >= CHANNEL_COUNT) return null;
        StringBuilder out = new StringBuilder(160);
        appendEntryJson(out, channels[id]);
        return out.toString();
    }

    private void appendEntryJson(StringBuilder out, Entry entry) {
        out.append("{\"id\":").append(entry.id);
        out.append(",\"name\":").append(JsonUtil.nullableString(entry.name));
        out.append(",\"rxMHz\":").append(JsonUtil.nullableDouble(entry.rxFreqHz));
        out.append(",\"txMHz\":").append(JsonUtil.nullableDouble(entry.txFreqHz));
        out.append(",\"status\":").append(JsonUtil.quote(entry.status));
        out.append(",\"stale\":").append(entry.stale);
        out.append(",\"readAtMs\":").append(nullableTime(entry.readAtMs));
        out.append(",\"attemptedAtMs\":").append(nullableTime(entry.attemptedAtMs));
        out.append(",\"errorCode\":").append(JsonUtil.nullableString(entry.errorCode));
        out.append('}');
    }

    private void markFailureLocked(Entry entry, String status, String code, long now) {
        entry.status = status;
        entry.stale = entry.readAtMs > 0L;
        entry.errorCode = safeErrorCode(code);
        lastErrorCode = entry.errorCode;
        // A failure never invents values; the last successful values remain visible.
    }

    private void finishGenerationLocked() {
        int success = successCountLocked();
        state = success == CHANNEL_COUNT ? STATE_COMPLETE : STATE_PARTIAL;
        lastRefreshAtMs = clock.nowMs();
        if (state.equals(STATE_COMPLETE)) lastErrorCode = null;
        inFlight = false;
        inFlightChannel = -1;
        inFlightRequestId = 0L;
    }

    private int nextPendingChannel() {
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            if (CHANNEL_PENDING.equals(channels[i].status)) return i;
        }
        return -1;
    }

    private int successCountLocked() {
        int count = 0;
        for (Entry entry : channels) if (CHANNEL_OK.equals(entry.status)) count++;
        return count;
    }

    private int completedCountLocked() {
        int count = 0;
        for (Entry entry : channels) {
            if (entry.status != null && !CHANNEL_PENDING.equals(entry.status)) count++;
        }
        return count;
    }

    private boolean anyStaleLocked() {
        for (Entry entry : channels) if (entry.stale) return true;
        return false;
    }

    private boolean schedulePumpLocked(long delayMs) {
        if (retryScheduled) return true;
        retryScheduled = true;
        try {
            pumpScheduler.schedule(() -> {
                synchronized (ChannelCatalogService.this) { retryScheduled = false; }
                pump();
            }, Math.max(0L, delayMs));
            return true;
        } catch (RuntimeException e) {
            retryScheduled = false;
            state = STATE_OFFLINE;
            lastErrorCode = "CATALOG_SCHEDULER_UNAVAILABLE";
            inFlight = false;
            inFlightChannel = -1;
            inFlightRequestId = 0L;
            return false;
        }
    }

    private static String safeErrorCode(String value) {
        if (value == null || value.trim().isEmpty()) return "CATALOG_ERROR";
        String result = value.trim();
        return result.length() > 48 ? result.substring(0, 48) : result;
    }

    private static String nullableTime(long value) {
        return value <= 0L ? "null" : Long.toString(value);
    }

    private static Range validRange(Integer rx, Integer tx) {
        if (rx == null || tx == null) return Range.INVALID;
        if (rx < 0 || tx < 0 || rx > 1_000_000_000 || tx > 1_000_000_000) {
            return Range.INVALID;
        }
        if (rx == 0 && tx == 0) return Range.EMPTY;
        if (rx == 0 || tx == 0) return Range.INVALID;
        return Range.VALID;
    }

    private static final class Range {
        static final Range VALID = new Range(true, false);
        static final Range EMPTY = new Range(true, true);
        static final Range INVALID = new Range(false, false);
        final boolean valid;
        final boolean empty;
        Range(boolean valid, boolean empty) {
            this.valid = valid;
            this.empty = empty;
        }
    }
}
