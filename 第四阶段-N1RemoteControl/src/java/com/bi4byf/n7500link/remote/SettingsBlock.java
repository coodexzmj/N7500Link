package com.bi4byf.n7500link.remote;

import java.util.Arrays;

/** Immutable copy of a complete READ_SETTINGS payload used for safe read-modify-write. */
public final class SettingsBlock {
    private final byte[] payload;
    public final int channelA;
    public final int channelB;
    public final String side;
    public final boolean scan;
    public final int squelch;

    private SettingsBlock(byte[] value, int channelA, int channelB, String side,
                          boolean scan, int squelch) {
        this.payload = value;
        this.channelA = channelA;
        this.channelB = channelB;
        this.side = side;
        this.scan = scan;
        this.squelch = squelch;
    }

    public static SettingsBlock fromSettingsBody(byte[] body) {
        if (body == null || body.length < 17) {
            throw new IllegalArgumentException("READ_SETTINGS 帧体不足 17 字节");
        }
        if (!GaiaCodec.isResponse(body)
                || GaiaCodec.command(body) != GaiaCodec.CMD_READ_SETTINGS
                || GaiaCodec.status(body) != 0) {
            throw new IllegalArgumentException("不是成功的 READ_SETTINGS 响应");
        }
        return fromPayload(Arrays.copyOfRange(body, 5, body.length));
    }

    static SettingsBlock fromPayload(byte[] source) {
        if (source == null || source.length < 10) {
            throw new IllegalArgumentException("设置块不足 10 字节");
        }
        byte[] value = Arrays.copyOf(source, source.length);
        int a = ((value[0] & 0xF0) >> 4) + (value[9] & 0xF0);
        int b = (value[0] & 0x0F) + ((value[9] & 0x0F) << 4);
        int dual = (value[1] & 0x30) >> 4;
        String selected = dual == 1 ? "A" : (dual == 2 ? "B" : null);
        boolean scanning = (value[1] & 0x80) != 0;
        int sql = value[1] & 0x0F;
        return new SettingsBlock(value, a, b, selected, scanning, sql);
    }

    /** Returns a defensive copy of the untouched radio settings block. */
    public byte[] baselinePayload() {
        return Arrays.copyOf(payload, payload.length);
    }

    /** Returns a complete payload with only the scan flag changed. */
    public byte[] withScan(boolean enabled) {
        byte[] result = baselinePayload();
        result[1] = (byte) ((result[1] & 0x7F) | (enabled ? 0x80 : 0x00));
        return result;
    }

    /** Returns a complete payload with only the selected A/B side changed. */
    public byte[] withSide(String requestedSide) {
        if (!"A".equals(requestedSide) && !"B".equals(requestedSide)) {
            throw new IllegalArgumentException("仅允许切换到信道 A 或 B");
        }
        byte[] result = baselinePayload();
        int bits = "A".equals(requestedSide) ? 0x10 : 0x20;
        result[1] = (byte) ((result[1] & 0xCF) | bits);
        return result;
    }

    /** The full settings payload, not just selected fields, must match after readback. */
    public boolean matchesExpected(byte[] observedPayload, byte[] expectedPayload) {
        return observedPayload != null && expectedPayload != null
                && observedPayload.length == expectedPayload.length
                && Arrays.equals(observedPayload, expectedPayload);
    }

    public boolean matchesBaseline(byte[] observedPayload) {
        return matchesExpected(observedPayload, payload);
    }

    public boolean sameSettings(byte[] observedPayload) {
        if (observedPayload == null || observedPayload.length < 10) return false;
        try {
            SettingsBlock observed = fromPayload(observedPayload);
            return observed.channelA == channelA
                    && observed.channelB == channelB
                    && equalsNullable(observed.side, side)
                    && observed.scan == scan
                    && observed.squelch == squelch;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}