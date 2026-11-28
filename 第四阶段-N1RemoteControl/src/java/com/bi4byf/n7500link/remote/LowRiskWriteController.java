package com.bi4byf.n7500link.remote;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One-operation safety state machine for volume, scan and A/B selection.
 * Baseline read -> one write -> delayed readback -> at most one rollback.
 */
public final class LowRiskWriteController {
    public interface Port {
        boolean isControlReady();
        int epoch();
        boolean requestSettingsRead();
        boolean requestVolumeRead();
        boolean requestVolumeWrite(int volume);
        boolean requestSettingsWrite(byte[] completePayload);
        boolean hasPendingRead(boolean volume);
    }

    public static final String IDLE = "IDLE";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String BASELINE_READ = "BASELINE_READ";
    public static final String WRITING = "WRITING";
    public static final String READBACK = "READBACK";
    public static final String READBACK_OK = "READBACK_OK";
    public static final String READBACK_MISMATCH = "READBACK_MISMATCH";
    public static final String ROLLBACK_WRITING = "ROLLBACK_WRITING";
    public static final String ROLLBACK_OK = "ROLLBACK_OK";
    public static final String ROLLBACK_FAILED = "ROLLBACK_FAILED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String BUSY = "BUSY";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String LINK_LOST = "LINK_LOST";
    public static final String CANCELLED = "CANCELLED";

    private static final long READBACK_DELAY_MS = 900L;
    private static final long OPERATION_TIMEOUT_MS = 12000L;
    private static final long ROLLBACK_TIMEOUT_MS = 8000L;

    public static final class Submission {
        public final boolean accepted;
        public final int httpStatus;
        public final String code;
        public final String operationId;
        public final String state;

        private Submission(boolean accepted, int httpStatus, String code,
                           String operationId, String state) {
            this.accepted = accepted;
            this.httpStatus = httpStatus;
            this.code = code;
            this.operationId = operationId;
            this.state = state;
        }
    }

    public static final class View {
        public final boolean available;
        public final boolean busy;
        public final String state;
        public final String kind;
        public final String operationId;
        public final String target;
        public final String baselineDisplay;
        public final String expectedDisplay;
        public final String observedDisplay;
        public final String reason;
        public final long updatedAtMs;
        public final long epoch;
        public final boolean sent;
        public final boolean rollbackSent;

        private View(boolean available, boolean busy, String state, String kind,
                     String operationId, String target, String baselineDisplay,
                     String expectedDisplay, String observedDisplay, String reason,
                     long updatedAtMs, long epoch, boolean sent, boolean rollbackSent) {
            this.available = available;
            this.busy = busy;
            this.state = state;
            this.kind = kind;
            this.operationId = operationId;
            this.target = target;
            this.baselineDisplay = baselineDisplay;
            this.expectedDisplay = expectedDisplay;
            this.observedDisplay = observedDisplay;
            this.reason = reason;
            this.updatedAtMs = updatedAtMs;
            this.epoch = epoch;
            this.sent = sent;
            this.rollbackSent = rollbackSent;
        }
    }

    private static final class Operation {
        final String id;
        final String kind;
        final int epoch;
        final int volumeTarget;
        final Boolean scanTarget;
        final String sideTarget;
        String state = ACCEPTED;
        String reason;
        long updatedAtMs = System.currentTimeMillis();
        boolean terminal;
        boolean baselineReady;
        boolean writeAttempted;
        boolean sent;
        boolean rollbackStarted;
        boolean rollbackSent;
        String baselineDisplay;
        String expectedDisplay;
        String observedDisplay;
        SettingsBlock baselineBlock;
        Integer baselineVolume;
        byte[] expectedSettings;

        Operation(String id, String kind, int epoch, int volumeTarget,
                  Boolean scanTarget, String sideTarget) {
            this.id = id;
            this.kind = kind;
            this.epoch = epoch;
            this.volumeTarget = volumeTarget;
            this.scanTarget = scanTarget;
            this.sideTarget = sideTarget;
        }

        boolean isVolume() { return "VOLUME".equals(kind); }
        String targetText() {
            if (isVolume()) return Integer.toString(volumeTarget);
            if (scanTarget != null) return scanTarget ? "ON" : "OFF";
            return sideTarget;
        }
    }

    private final Port port;
    private final Executor callbacks;
    private final ScheduledExecutorService timers;
    private final Object lock = new Object();
    private long sequence;
    private Operation active;

    public LowRiskWriteController(Port port, Executor callbacks,
                                  ScheduledExecutorService timers) {
        this.port = port;
        this.callbacks = callbacks;
        this.timers = timers;
    }

    public Submission submitVolume(int volume) {
        if (volume < 0 || volume > 15) {
            return rejected(400, "VOLUME_OUT_OF_RANGE");
        }
        return submit("VOLUME", volume, null, null);
    }

    public Submission submitScan(boolean enabled) {
        return submit("SCAN", -1, enabled, null);
    }

    public Submission submitSide(String side) {
        if (!"A".equals(side) && !"B".equals(side)) {
            return rejected(400, "SIDE_OUT_OF_RANGE");
        }
        return submit("SIDE", -1, null, side);
    }

    private Submission submit(String kind, int volumeTarget,
                              Boolean scanTarget, String sideTarget) {
        Operation accepted;
        synchronized (lock) {
            if (!port.isControlReady()) {
                return new Submission(false, 503, "RADIO_NOT_READY", null, UNAVAILABLE);
            }
            if (active != null && !active.terminal) {
                return new Submission(false, 409, "CONTROL_BUSY", active.id, BUSY);
            }
            sequence++;
            accepted = new Operation("ctl-" + Long.toHexString(sequence), kind,
                    port.epoch(), volumeTarget, scanTarget, sideTarget);
            active = accepted;
        }
        if (!scheduleOperationTimeout(accepted)) {
            finish(accepted, TIMEOUT, "OPERATION_TIMER_UNAVAILABLE");
            return new Submission(false, 503, "CONTROL_TIMER_UNAVAILABLE",
                    accepted.id, TIMEOUT);
        }
        try {
            callbacks.execute(() -> beginBaseline(accepted.id));
        } catch (RuntimeException e) {
            finish(accepted, TIMEOUT, "CALLBACK_EXECUTOR_UNAVAILABLE");
            return new Submission(false, 503, "CONTROL_CALLBACK_UNAVAILABLE",
                    accepted.id, TIMEOUT);
        }
        synchronized (lock) {
            return new Submission(true, 202, "CONTROL_ACCEPTED",
                    accepted.id, accepted.state);
        }
    }

    private boolean scheduleOperationTimeout(Operation operation) {
        try {
            timers.schedule(() -> onOperationTimeout(operation.id),
                    OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean scheduleRollbackTimeout(Operation operation) {
        try {
            timers.schedule(() -> onRollbackTimeout(operation.id),
                    ROLLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Submission rejected(int status, String code) {
        return new Submission(false, status, code, null, UNAVAILABLE);
    }

    public boolean isBusy() {
        synchronized (lock) { return active != null && !active.terminal; }
    }

    public void onReadResult(CommandScheduler.ReadResult result) {
        if (result == null || result.stale) return;
        String state;
        synchronized (lock) {
            Operation op = active;
            if (op == null || op.terminal || op.epoch != result.epoch) return;
            state = op.state;
            if (BASELINE_READ.equals(state)) {
                if (!kindMatches(op, result)) return;
            } else if (READBACK.equals(state)) {
                if (!kindMatches(op, result)) return;
            } else if (ROLLBACK_WRITING.equals(state)) {
                return;
            } else {
                return;
            }
        }
        if (result.parsed == null || !result.parsed.success) {
            handleReadFailure(result, state);
            return;
        }
        if (BASELINE_READ.equals(state)) {
            applyBaseline(result);
        } else if (READBACK.equals(state)) {
            applyReadback(result);
        }
    }

    private static boolean kindMatches(Operation op, CommandScheduler.ReadResult result) {
        return op.isVolume()
                ? result.kind == CommandScheduler.VOLUME
                : result.kind == CommandScheduler.SETTINGS;
    }

    private void handleReadFailure(CommandScheduler.ReadResult result, String expectedState) {
        Operation op = activeMatching(result.epoch, expectedState);
        if (op == null) return;
        if (BASELINE_READ.equals(expectedState)) {
            finish(op, TIMEOUT, result.timedOut ? "BASELINE_TIMEOUT" : "BASELINE_READ_FAILED");
            return;
        }
        synchronized (lock) {
            if (op.terminal || !READBACK.equals(op.state)) return;
        }
        if (op.writeAttempted) {
            beginRollback(op, result.timedOut ? "READBACK_TIMEOUT" : "READBACK_FAILED");
        } else {
            finish(op, TIMEOUT, "READBACK_FAILED");
        }
    }

    public void onWriteResult(CommandScheduler.WriteResult result) {
        if (result == null || result.stale) return;
        Operation op;
        boolean scheduleNormal = false;
        boolean scheduleRollback = false;
        synchronized (lock) {
            op = active;
            if (op == null || op.terminal || op.epoch != result.epoch) return;
            if (WRITING.equals(op.state)) {
                if ((op.isVolume() && result.kind != CommandScheduler.VOLUME_WRITE)
                        || (!op.isVolume() && result.kind != CommandScheduler.SETTINGS_WRITE)) return;
                op.sent = result.sent;
                if (!result.sent) {
                    op.state = TIMEOUT;
                    op.reason = safeReason(result.errorCode, "WRITE_NOT_SENT");
                    op.terminal = true;
                    touch(op);
                    return;
                }
                op.reason = "WAIT_READBACK";
                touch(op);
                scheduleNormal = true;
            } else if (ROLLBACK_WRITING.equals(op.state)) {
                op.rollbackSent = result.sent;
                if (!result.sent) {
                    op.state = ROLLBACK_FAILED;
                    op.reason = safeReason(result.errorCode, "ROLLBACK_NOT_SENT");
                    op.terminal = true;
                    touch(op);
                    return;
                }
                op.reason = "WAIT_ROLLBACK_READBACK";
                touch(op);
                scheduleRollback = true;
            } else {
                return;
            }
        }
        if (scheduleNormal) scheduleDelayed(op.id, false);
        if (scheduleRollback) scheduleDelayed(op.id, true);
    }

    public void cancel(String reason) {
        cancel(LINK_LOST, reason);
    }

    public void cancel(String state, String reason) {
        Operation op;
        synchronized (lock) {
            op = active;
            if (op == null || op.terminal) return;
            op.state = safeState(state);
            op.reason = safeReason(reason, op.state);
            op.terminal = true;
            touch(op);
        }
    }

    public View view() {
        boolean available = port.isControlReady();
        int epoch = port.epoch();
        synchronized (lock) {
            if (active == null) {
                return new View(available, false, IDLE, null, null,
                        null, null, null, null, null, System.currentTimeMillis(),
                        epoch, false, false);
            }
            return new View(available, !active.terminal, active.state,
                    active.kind, active.id, active.targetText(),
                    active.baselineDisplay, active.expectedDisplay, active.observedDisplay,
                    active.reason, active.updatedAtMs, active.epoch, active.sent,
                    active.rollbackSent);
        }
    }

    public String statusJson() {
        View value = view();
        return "{\"ok\":true"
                + ",\"available\":" + value.available
                + ",\"busy\":" + value.busy
                + ",\"state\":" + JsonUtil.quote(value.state)
                + ",\"kind\":" + JsonUtil.nullableString(value.kind)
                + ",\"operationId\":" + JsonUtil.nullableString(value.operationId)
                + ",\"target\":" + JsonUtil.nullableString(value.target)
                + ",\"baselineDisplay\":" + JsonUtil.nullableString(value.baselineDisplay)
                + ",\"expectedDisplay\":" + JsonUtil.nullableString(value.expectedDisplay)
                + ",\"observedDisplay\":" + JsonUtil.nullableString(value.observedDisplay)
                + ",\"reason\":" + JsonUtil.nullableString(value.reason)
                + ",\"updatedAt\":" + JsonUtil.quote(GatewayContract.utc(value.updatedAtMs))
                + ",\"epoch\":" + value.epoch
                + ",\"sent\":" + value.sent
                + ",\"rollbackSent\":" + value.rollbackSent
                + '}';
    }

    private void beginBaseline(String operationId) {
        Operation op = current(operationId);
        if (op == null) return;
        synchronized (lock) {
            if (op.terminal || !ACCEPTED.equals(op.state) || active != op) return;
            op.state = BASELINE_READ;
            op.reason = "BASELINE";
            touch(op);
        }
        if (!port.isControlReady() || op.epoch != port.epoch()) {
            finish(op, LINK_LOST, "EPOCH_CHANGED");
            return;
        }
        boolean volume = op.isVolume();
        boolean accepted = volume ? port.requestVolumeRead() : port.requestSettingsRead();
        if (!accepted && !port.hasPendingRead(volume)) {
            finish(op, UNAVAILABLE, "BASELINE_READ_NOT_ACCEPTED");
        }
    }

    private void applyBaseline(CommandScheduler.ReadResult result) {
        Operation op = activeMatching(result.epoch, BASELINE_READ);
        if (op == null) return;
        if (op.isVolume()) {
            Integer observed = result.parsed.volume;
            if (observed == null) {
                finish(op, TIMEOUT, "VOLUME_BASELINE_INVALID");
                return;
            }
            synchronized (lock) {
                if (op.terminal || !BASELINE_READ.equals(op.state)) return;
                op.baselineVolume = observed;
                op.baselineDisplay = volumeDisplay(observed);
                op.expectedDisplay = volumeDisplay(op.volumeTarget);
                op.observedDisplay = volumeDisplay(observed);
                op.baselineReady = true;
                if (observed.intValue() == op.volumeTarget) {
                    op.state = READBACK_OK;
                    op.reason = "NO_CHANGE";
                    op.terminal = true;
                    touch(op);
                    return;
                }
            }
            sendVolumeWrite(op);
        } else {
            SettingsBlock baseline = result.parsed.settingsBlock;
            if (baseline == null) {
                finish(op, TIMEOUT, "SETTINGS_BASELINE_INVALID");
                return;
            }
            byte[] expected;
            synchronized (lock) {
                if (op.terminal || !BASELINE_READ.equals(op.state)) return;
                try {
                    expected = op.scanTarget != null
                            ? baseline.withScan(op.scanTarget.booleanValue())
                            : baseline.withSide(op.sideTarget);
                } catch (IllegalArgumentException e) {
                    op.state = TIMEOUT;
                    op.reason = "SETTINGS_TARGET_INVALID";
                    op.terminal = true;
                    touch(op);
                    return;
                }
                op.baselineBlock = baseline;
                op.expectedSettings = Arrays.copyOf(expected, expected.length);
                op.baselineDisplay = settingsDisplay(baseline);
                op.expectedDisplay = settingsDisplay(SettingsBlock.fromPayload(expected));
                op.observedDisplay = op.baselineDisplay;
                op.baselineReady = true;
                if (baseline.matchesBaseline(expected)) {
                    op.state = READBACK_OK;
                    op.reason = "NO_CHANGE";
                    op.terminal = true;
                    touch(op);
                    return;
                }
            }
            sendSettingsWrite(op, expected);
        }
    }

    private void sendVolumeWrite(Operation op) {
        int target;
        synchronized (lock) {
            if (op.terminal || !BASELINE_READ.equals(op.state) || active != op) return;
            op.state = WRITING;
            op.reason = "WRITE_PENDING";
            op.writeAttempted = true;
            target = op.volumeTarget;
            touch(op);
        }
        if (!port.isControlReady() || op.epoch != port.epoch()) {
            finish(op, LINK_LOST, "EPOCH_CHANGED");
            return;
        }
        if (!port.requestVolumeWrite(target)) {
            finish(op, TIMEOUT, "WRITE_NOT_ACCEPTED_NO_RETRY");
        }
    }

    private void sendSettingsWrite(Operation op, byte[] expected) {
        byte[] payload;
        synchronized (lock) {
            if (op.terminal || !BASELINE_READ.equals(op.state) || active != op) return;
            op.state = WRITING;
            op.reason = "WRITE_PENDING";
            op.writeAttempted = true;
            payload = copy(expected);
            touch(op);
        }
        if (!port.isControlReady() || op.epoch != port.epoch()) {
            finish(op, LINK_LOST, "EPOCH_CHANGED");
            return;
        }
        if (payload == null || !port.requestSettingsWrite(payload)) {
            finish(op, TIMEOUT, "WRITE_NOT_ACCEPTED_NO_RETRY");
        }
    }

    private void scheduleDelayed(String operationId, boolean rollbackReadback) {
        try {
            timers.schedule(() -> dispatchReadback(operationId, rollbackReadback),
                    READBACK_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            Operation op = current(operationId);
            if (op != null) {
                finish(op, rollbackReadback ? ROLLBACK_FAILED : TIMEOUT,
                        "READBACK_TIMER_FAILED");
            }
        }
    }

    private void dispatchReadback(String operationId, boolean rollbackReadback) {
        try {
            callbacks.execute(() -> beginReadback(operationId, rollbackReadback));
        } catch (RuntimeException e) {
            Operation op = current(operationId);
            if (op != null) {
                finish(op, rollbackReadback ? ROLLBACK_FAILED : TIMEOUT,
                        "READBACK_EXECUTOR_UNAVAILABLE");
            }
        }
    }

    private void beginReadback(String operationId, boolean rollbackReadback) {
        Operation op = current(operationId);
        if (op == null) return;
        synchronized (lock) {
            if (op.terminal || active != op) return;
            if (rollbackReadback) {
                if (!ROLLBACK_WRITING.equals(op.state)) return;
                op.reason = "ROLLBACK_READBACK";
            } else {
                if (!WRITING.equals(op.state)) return;
                op.reason = "READBACK";
            }
            op.state = READBACK;
            touch(op);
        }
        if (!port.isControlReady() || op.epoch != port.epoch()) {
            finish(op, LINK_LOST, "EPOCH_CHANGED");
            return;
        }
        boolean volume = op.isVolume();
        boolean accepted = volume ? port.requestVolumeRead() : port.requestSettingsRead();
        if (!accepted && !port.hasPendingRead(volume)) {
            if (rollbackReadback) {
                finish(op, ROLLBACK_FAILED, "ROLLBACK_READBACK_NOT_ACCEPTED");
            } else if (op.writeAttempted) {
                beginRollback(op, "READBACK_NOT_ACCEPTED");
            } else {
                finish(op, TIMEOUT, "READBACK_NOT_ACCEPTED");
            }
        }
    }

    private void applyReadback(CommandScheduler.ReadResult result) {
        Operation op = activeMatching(result.epoch, READBACK);
        if (op == null) return;
        boolean rollbackReadback;
        Integer baselineVolume;
        int volumeTarget;
        byte[] baselineSettings;
        byte[] expectedSettings;
        synchronized (lock) {
            if (op.terminal || !READBACK.equals(op.state)) return;
            rollbackReadback = "ROLLBACK_READBACK".equals(op.reason);
            baselineVolume = op.baselineVolume;
            volumeTarget = op.volumeTarget;
            baselineSettings = op.baselineBlock == null ? null : op.baselineBlock.baselinePayload();
            expectedSettings = copy(op.expectedSettings);
        }
        boolean matches;
        if (op.isVolume()) {
            Integer observed = result.parsed.volume;
            Integer expected = rollbackReadback ? baselineVolume : Integer.valueOf(volumeTarget);
            matches = observed != null && expected != null && observed.intValue() == expected.intValue();
            synchronized (lock) { op.observedDisplay = volumeDisplay(observed); touch(op); }
        } else {
            SettingsBlock observedBlock = result.parsed.settingsBlock;
            synchronized (lock) { op.observedDisplay = settingsDisplay(observedBlock); touch(op); }
            if (observedBlock == null || baselineSettings == null) {
                matches = false;
            } else {
                byte[] expected = rollbackReadback ? baselineSettings : expectedSettings;
                byte[] observed = observedBlock.baselinePayload();
                matches = observed != null && expected != null && Arrays.equals(observed, expected);
            }
        }
        if (matches) {
            finish(op, rollbackReadback ? ROLLBACK_OK : READBACK_OK,
                    rollbackReadback ? "BASELINE_RESTORED" : "TARGET_CONFIRMED");
        } else if (rollbackReadback) {
            finish(op, ROLLBACK_FAILED, "ROLLBACK_READBACK_MISMATCH");
        } else {
            synchronized (lock) { op.state = READBACK_MISMATCH; op.reason = "TARGET_READBACK_MISMATCH"; touch(op); }
            beginRollback(op, "TARGET_READBACK_MISMATCH");
        }
    }

    private void beginRollback(Operation op, String reason) {
        boolean volume;
        int volumeValue = -1;
        byte[] settingsPayload = null;
        synchronized (lock) {
            if (op.terminal || op.rollbackStarted || active != op) return;
            if (!op.baselineReady) {
                op.state = ROLLBACK_FAILED;
                op.reason = "ROLLBACK_BASELINE_MISSING";
                op.terminal = true;
                touch(op);
                return;
            }
            volume = op.isVolume();
            if (volume) {
                if (op.baselineVolume == null) {
                    op.state = ROLLBACK_FAILED;
                    op.reason = "ROLLBACK_BASELINE_MISSING";
                    op.terminal = true;
                    touch(op);
                    return;
                }
                volumeValue = op.baselineVolume.intValue();
            } else {
                if (op.baselineBlock == null) {
                    op.state = ROLLBACK_FAILED;
                    op.reason = "ROLLBACK_BASELINE_MISSING";
                    op.terminal = true;
                    touch(op);
                    return;
                }
                settingsPayload = op.baselineBlock.baselinePayload();
            }
            op.rollbackStarted = true;
            op.state = ROLLBACK_WRITING;
            op.reason = safeReason(reason, "ROLLBACK");
            op.writeAttempted = true;
            touch(op);
        }
        if (!scheduleRollbackTimeout(op)) {
            finish(op, ROLLBACK_FAILED, "ROLLBACK_TIMER_UNAVAILABLE");
            return;
        }
        if (!port.isControlReady() || op.epoch != port.epoch()) {
            finish(op, ROLLBACK_FAILED, "ROLLBACK_LINK_LOST");
            return;
        }
        boolean accepted = volume
                ? port.requestVolumeWrite(volumeValue)
                : port.requestSettingsWrite(settingsPayload);
        if (!accepted) {
            finish(op, ROLLBACK_FAILED, "ROLLBACK_NOT_ACCEPTED_NO_RETRY");
        }
    }

    private void onOperationTimeout(String operationId) {
        Operation op = current(operationId);
        if (op == null) return;
        boolean useRollback;
        synchronized (lock) {
            if (op.terminal) return;
            if (op.rollbackStarted) return;
            useRollback = op.writeAttempted && op.baselineReady;
        }
        if (useRollback) {
            beginRollback(op, "OPERATION_TIMEOUT");
        } else {
            finish(op, TIMEOUT, "OPERATION_TIMEOUT");
        }
    }

    private void onRollbackTimeout(String operationId) {
        Operation op = current(operationId);
        if (op == null) return;
        synchronized (lock) {
            if (op.terminal || !op.rollbackStarted) return;
            op.state = ROLLBACK_FAILED;
            op.reason = "ROLLBACK_TIMEOUT";
            op.terminal = true;
            touch(op);
        }
    }

    private Operation current(String operationId) {
        synchronized (lock) {
            if (active == null || !active.id.equals(operationId)) return null;
            return active;
        }
    }

    private Operation activeMatching(int epoch, String expectedState) {
        synchronized (lock) {
            Operation op = active;
            if (op == null || op.terminal || op.epoch != epoch
                    || !expectedState.equals(op.state)) return null;
            return op;
        }
    }

    private void finish(Operation op, String state, String reason) {
        synchronized (lock) {
            if (op.terminal || active != op) return;
            op.state = state;
            op.reason = safeReason(reason, state);
            op.terminal = true;
            touch(op);
        }
    }

    private static String volumeDisplay(Integer value) {
        return value == null ? "音量未知" : "音量 " + value.intValue();
    }

    private static String settingsDisplay(SettingsBlock block) {
        if (block == null) return "设置未知";
        String side = block.side == null ? "未选择" : block.side;
        return "扫描 " + (block.scan ? "ON" : "OFF")
                + " · 当前 " + side
                + " · 静噪 " + block.squelch;
    }
    private static String safeState(String value) {
        return CANCELLED.equals(value) ? CANCELLED : LINK_LOST;
    }

    private static void touch(Operation op) {
        op.updatedAtMs = System.currentTimeMillis();
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static String safeReason(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String result = value.trim();
        return result.length() > 80 ? result.substring(0, 80) : result;
    }
}
