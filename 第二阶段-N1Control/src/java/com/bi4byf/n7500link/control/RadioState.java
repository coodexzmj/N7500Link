package com.bi4byf.n7500link.control;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

/**
 * Structured snapshots of the only radio fields that stage 2 is allowed to
 * change. The full READ_SETTINGS payload is always retained so that a write
 * never has to rebuild unrelated settings from zero.
 */
final class RadioState {
    private RadioState() {}

    static final class SettingsSnapshot {
        final byte[] responseBody;
        final byte[] writePayload;
        final int channelA;
        final int channelB;
        final int doubleChannel;
        final boolean scan;
        final int squelch;

        private SettingsSnapshot(byte[] responseBody, byte[] writePayload,
                                 int channelA, int channelB, int doubleChannel,
                                 boolean scan, int squelch) {
            this.responseBody = responseBody;
            this.writePayload = writePayload;
            this.channelA = channelA;
            this.channelB = channelB;
            this.doubleChannel = doubleChannel;
            this.scan = scan;
            this.squelch = squelch;
        }

        static SettingsSnapshot fromResponse(byte[] body) {
            if (body == null || body.length < 17) {
                throw new IllegalArgumentException("READ_SETTINGS 帧体不足 17 字节");
            }
            if (!GaiaCodec.isResponse(body)
                    || GaiaCodec.command(body) != GaiaCodec.CMD_READ_SETTINGS
                    || GaiaCodec.status(body) != 0) {
                throw new IllegalArgumentException("不是成功的 READ_SETTINGS 响应");
            }
            byte[] payload = Arrays.copyOfRange(body, 5, body.length);
            int channelA = ((payload[0] & 0xF0) >> 4) + (payload[9] & 0xF0);
            int channelB = (payload[0] & 0x0F) + ((payload[9] & 0x0F) << 4);
            int doubleChannel = (payload[1] & 0x30) >> 4;
            boolean scan = (payload[1] & 0x80) != 0;
            int squelch = payload[1] & 0x0F;
            return new SettingsSnapshot(Arrays.copyOf(body, body.length), payload,
                    channelA, channelB, doubleChannel, scan, squelch);
        }

        /**
         * Builds a WRITE_SETTINGS payload by copying the complete original
         * settings block and changing only the requested fields.
         */
        byte[] buildPayload(Integer newChannelA, Integer newChannelB, Integer newDoubleChannel,
                            Boolean newScan, Integer newSquelch) {
            int a = newChannelA == null ? channelA : newChannelA;
            int b = newChannelB == null ? channelB : newChannelB;
            int dual = newDoubleChannel == null ? doubleChannel : newDoubleChannel;
            boolean scanning = newScan == null ? scan : newScan;
            int sql = newSquelch == null ? squelch : newSquelch;
            validateChannel(a, "信道 A");
            validateChannel(b, "信道 B");
            if (dual < 0 || dual > 3) throw new IllegalArgumentException("双信道模式超出 0..3");
            if (sql < 0 || sql > 15) throw new IllegalArgumentException("静噪超出 0..15");
            if (writePayload.length < 10) throw new IllegalArgumentException("设置块不足 10 字节");

            byte[] output = Arrays.copyOf(writePayload, writePayload.length);
            output[0] = (byte) (((a & 0x0F) << 4) | (b & 0x0F));
            output[1] = (byte) ((output[1] & 0x7F)
                    | (scanning ? 0x80 : 0x00));
            output[1] = (byte) ((output[1] & 0xCF) | ((dual & 0x03) << 4));
            output[1] = (byte) ((output[1] & 0xF0) | (sql & 0x0F));
            output[9] = (byte) ((a & 0xF0) | ((b >> 4) & 0x0F));
            return output;
        }

        boolean matchesPayload(byte[] expected) {
            return expected != null && expected.length == writePayload.length
                    && Arrays.equals(writePayload, expected);
        }

        boolean matches(int expectedA, int expectedB, int expectedDual,
                        boolean expectedScan, int expectedSquelch) {
            return channelA == expectedA
                    && channelB == expectedB
                    && doubleChannel == expectedDual
                    && scan == expectedScan
                    && squelch == expectedSquelch;
        }

        String summary() {
            String[] mode = {"关闭", "信道 A", "信道 B", "VFO C"};
            return String.format(Locale.US,
                    "A=%d  B=%d  当前=%s  扫描=%s  静噪=%d",
                    channelA, channelB, mode[Math.min(doubleChannel, mode.length - 1)],
                    scan ? "开" : "关", squelch);
        }

        private static void validateChannel(int channel, String label) {
            if (channel < 0 || channel > 255) {
                throw new IllegalArgumentException(label + "索引超出 0..255");
            }
        }
    }

    static final class VolumeSnapshot {
        final int volume;

        private VolumeSnapshot(int volume) { this.volume = volume; }

        static VolumeSnapshot fromResponse(byte[] body) {
            if (body == null || body.length < 6) {
                throw new IllegalArgumentException("GET_VOLUME 帧体不足 6 字节");
            }
            if (!GaiaCodec.isResponse(body)
                    || GaiaCodec.command(body) != GaiaCodec.CMD_GET_VOLUME
                    || GaiaCodec.status(body) != 0) {
                throw new IllegalArgumentException("不是成功的 GET_VOLUME 响应");
            }
            int value = HexUtil.u8(body, 5);
            if (value < 0 || value > 15) {
                throw new IllegalArgumentException("音量值超出 0..15：" + value);
            }
            return new VolumeSnapshot(value);
        }
    }

    static final class HtStatusSnapshot {
        final boolean powerOn;
        final boolean tx;
        final boolean rx;
        final boolean scan;
        final int currentChannel;
        final int rssi;

        private HtStatusSnapshot(boolean powerOn, boolean tx, boolean rx,
                                 boolean scan, int currentChannel, int rssi) {
            this.powerOn = powerOn;
            this.tx = tx;
            this.rx = rx;
            this.scan = scan;
            this.currentChannel = currentChannel;
            this.rssi = rssi;
        }

        static HtStatusSnapshot fromResponse(byte[] body) {
            if (body == null || body.length < 9) {
                throw new IllegalArgumentException("GET_HT_STATUS 帧体不足 9 字节");
            }
            if (!GaiaCodec.isResponse(body)
                    || GaiaCodec.command(body) != GaiaCodec.CMD_GET_HT_STATUS
                    || GaiaCodec.status(body) != 0) {
                throw new IllegalArgumentException("不是成功的 GET_HT_STATUS 响应");
            }
            int flags = HexUtil.u8(body, 5);
            int currentChannel = (((HexUtil.u8(body, 8) & 0x3C) >> 2) << 4)
                    + ((HexUtil.u8(body, 6) & 0xF0) >> 4);
            int rssi = (HexUtil.u8(body, 7) & 0xF0) >> 4;
            return new HtStatusSnapshot((flags & 0x80) != 0, (flags & 0x40) != 0,
                    (flags & 0x10) != 0, (flags & 0x02) != 0, currentChannel, rssi);
        }
    }

    static final class ChannelSnapshot {
        final int channelId;
        final int rxFreq;
        final int txFreq;
        final String name;

        private ChannelSnapshot(int channelId, int rxFreq, int txFreq, String name) {
            this.channelId = channelId;
            this.rxFreq = rxFreq;
            this.txFreq = txFreq;
            this.name = name;
        }

        static ChannelSnapshot fromResponse(byte[] body) {
            if (body == null || body.length < 30) {
                throw new IllegalArgumentException("READ_RF_CH 帧体不足 30 字节");
            }
            if (!GaiaCodec.isResponse(body)
                    || GaiaCodec.command(body) != GaiaCodec.CMD_READ_RF_CH
                    || GaiaCodec.status(body) != 0) {
                throw new IllegalArgumentException("不是成功的 READ_RF_CH 响应");
            }
            int id = HexUtil.u8(body, 5);
            int rx = HexUtil.s32(body, 10) & 0x3FFFFFFF;
            int tx = HexUtil.s32(body, 6) & 0x3FFFFFFF;
            return new ChannelSnapshot(id, rx, tx, decodeGbk(body, 20, Math.min(10, body.length - 20)));
        }

        String summary() {
            return "CH " + channelId + " / " + (name.isEmpty() ? "(未命名)" : name)
                    + " / RX " + HexUtil.frequencyMHz(rxFreq)
                    + " / TX " + HexUtil.frequencyMHz(txFreq);
        }
    }

    /**
     * GAIA responses do not carry a request identifier. Keep at most one
     * outstanding request per command so a late or duplicate response cannot
     * be handed to a second request for the same command.
     */
    static final class InFlightRequestGuard {
        private final HashSet<Integer> commands = new HashSet<>();
        private int rejected;

        synchronized boolean tryBegin(int command) {
            if (!commands.add(command)) {
                rejected++;
                return false;
            }
            return true;
        }

        synchronized void finish(int command) {
            commands.remove(command);
        }

        synchronized boolean isPending(int command) {
            return commands.contains(command);
        }

        synchronized int rejectedCount() {
            return rejected;
        }
    }

    /** Enforces one and only one state-changing operation at a time. */
    static final class WriteGuard {
        private boolean busy;
        private String activeLabel;
        private int completed;
        private int rejected;

        synchronized boolean tryBegin(String label) {
            if (busy) {
                rejected++;
                return false;
            }
            busy = true;
            activeLabel = label;
            return true;
        }

        synchronized void finish() {
            if (busy) completed++;
            busy = false;
            activeLabel = null;
        }

        synchronized boolean isBusy() { return busy; }
        synchronized String activeLabel() { return activeLabel; }
        synchronized int completedCount() { return completed; }
        synchronized int rejectedCount() { return rejected; }
    }

    private static String decodeGbk(byte[] data, int offset, int length) {
        if (length <= 0) return "";
        int end = offset + length;
        while (offset < end && data[offset] == 0) return "";
        int actualEnd = offset;
        while (actualEnd < end && data[actualEnd] != 0) actualEnd++;
        byte[] nameBytes = new byte[actualEnd - offset];
        System.arraycopy(data, offset, nameBytes, 0, nameBytes.length);
        try {
            return new String(nameBytes, "GBK").trim();
        } catch (UnsupportedEncodingException e) {
            return new String(nameBytes).trim();
        }
    }
}