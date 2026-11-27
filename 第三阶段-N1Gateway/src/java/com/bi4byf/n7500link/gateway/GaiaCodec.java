package com.bi4byf.n7500link.gateway;

import java.util.Arrays;

/** GAIA framing and the G1 read-only command allowlist. */
public final class GaiaCodec {
    public static final int GROUP_BASIC = 0x0002;
    public static final int CMD_GET_DEV_INFO = 4;
    public static final int CMD_REGISTER_NOTIFICATION = 6;
    public static final int CMD_EVENT_NOTIFICATION = 9;
    public static final int CMD_READ_SETTINGS = 10;
    public static final int CMD_READ_RF_CH = 13;
    public static final int CMD_GET_HT_STATUS = 20;
    public static final int CMD_GET_VOLUME = 22;

    private byte[] buffer = new byte[256];
    private int length;
    private volatile long rxFrames;
    private volatile long discardedBytes;

    public interface Listener {
        void onFrame(byte[] body);
        void onDiscarded(int byteCount);
    }

    public void reset() {
        length = 0;
        rxFrames = 0;
        discardedBytes = 0;
    }

    public void feed(byte[] chunk, Listener listener) {
        if (chunk == null || chunk.length == 0) return;
        ensureCapacity(length + chunk.length);
        System.arraycopy(chunk, 0, buffer, length, chunk.length);
        length += chunk.length;

        int position = 0;
        while (position < length) {
            int header = findHeader(position);
            if (header < 0) {
                int keepFrom = length > position && buffer[length - 1] == (byte) 0xFF
                        ? length - 1 : length;
                int dropped = keepFrom - position;
                if (dropped > 0) {
                    discardedBytes += dropped;
                    listener.onDiscarded(dropped);
                }
                position = keepFrom;
                break;
            }
            if (header > position) {
                int dropped = header - position;
                discardedBytes += dropped;
                listener.onDiscarded(dropped);
                position = header;
            }
            if (length - position < 4) break;
            int flags = buffer[position + 2] & 0xFF;
            int payloadLength = buffer[position + 3] & 0xFF;
            boolean checksum = (flags & 0x01) != 0;
            int totalLength = 8 + payloadLength + (checksum ? 1 : 0);
            if (length - position < totalLength) break;
            byte[] body = Arrays.copyOfRange(buffer, position + 4,
                    position + 4 + 4 + payloadLength);
            rxFrames++;
            listener.onFrame(body);
            position += totalLength;
        }

        int remaining = length - position;
        if (remaining > 0 && position > 0) {
            System.arraycopy(buffer, position, buffer, 0, remaining);
        }
        length = remaining;
    }

    public static boolean isAllowedReadCommand(int command) {
        return command == CMD_GET_DEV_INFO
                || command == CMD_REGISTER_NOTIFICATION
                || command == CMD_READ_SETTINGS
                || command == CMD_READ_RF_CH
                || command == CMD_GET_HT_STATUS
                || command == CMD_GET_VOLUME;
    }

    /** Named read-only builders are the only way the scheduler obtains frames. */
    public static byte[] deviceInfoRequest() {
        return buildReadFrame(CMD_GET_DEV_INFO, new byte[] {0x03});
    }

    public static byte[] notificationRegistrationRequest() {
        return buildReadFrame(CMD_REGISTER_NOTIFICATION, new byte[] {0x01, 0x0E});
    }

    public static byte[] settingsRequest() {
        return buildReadFrame(CMD_READ_SETTINGS, null);
    }

    public static byte[] statusRequest() {
        return buildReadFrame(CMD_GET_HT_STATUS, null);
    }

    public static byte[] volumeRequest() {
        return buildReadFrame(CMD_GET_VOLUME, null);
    }

    public static byte[] channelRequest(int channelId) {
        if (channelId < 0 || channelId > 255) {
            throw new IllegalArgumentException("信道编号超出 0..255");
        }
        return buildReadFrame(CMD_READ_RF_CH, new byte[] {(byte) channelId});
    }

    private static byte[] buildReadFrame(int command, byte[] payload) {
        if (!isAllowedReadCommand(command)) {
            throw new IllegalArgumentException("G1 拒绝非只读命令：" + command);
        }
        int payloadLength = payload == null ? 0 : payload.length;
        byte[] body = new byte[4 + payloadLength];
        body[0] = 0x00;
        body[1] = 0x02;
        body[2] = (byte) ((command >> 8) & 0xFF);
        body[3] = (byte) (command & 0xFF);
        if (payloadLength > 0) System.arraycopy(payload, 0, body, 4, payloadLength);
        byte[] frame = new byte[8 + payloadLength];
        frame[0] = (byte) 0xFF;
        frame[1] = 0x01;
        frame[2] = 0x00;
        frame[3] = (byte) payloadLength;
        System.arraycopy(body, 0, frame, 4, body.length);
        return frame;
    }

    public static int group(byte[] body) {
        return HexUtil.u16(body, 0);
    }

    public static int rawCommand(byte[] body) {
        return HexUtil.u16(body, 2);
    }

    public static int command(byte[] body) {
        return rawCommand(body) & 0x7FFF;
    }

    public static boolean isResponse(byte[] body) {
        return (rawCommand(body) & 0x8000) != 0;
    }

    public static int status(byte[] body) {
        return body != null && body.length > 4 ? (body[4] & 0xFF) : -1;
    }

    public static String commandName(int command) {
        switch (command) {
            case CMD_GET_DEV_INFO: return "GET_DEV_INFO";
            case CMD_REGISTER_NOTIFICATION: return "REGISTER_NOTIFICATION";
            case CMD_EVENT_NOTIFICATION: return "EVENT_NOTIFICATION";
            case CMD_READ_SETTINGS: return "READ_SETTINGS";
            case CMD_READ_RF_CH: return "READ_RF_CH";
            case CMD_GET_HT_STATUS: return "GET_HT_STATUS";
            case CMD_GET_VOLUME: return "GET_VOLUME";
            default: return "CMD_" + command;
        }
    }

    public long getRxFrames() { return rxFrames; }
    public long getDiscardedBytes() { return discardedBytes; }

    private int findHeader(int start) {
        for (int i = start; i + 1 < length; i++) {
            if (buffer[i] == (byte) 0xFF && buffer[i + 1] == 0x01) return i;
        }
        return -1;
    }

    private void ensureCapacity(int needed) {
        if (needed <= buffer.length) return;
        int next = buffer.length;
        while (next < needed) next *= 2;
        buffer = Arrays.copyOf(buffer, next);
    }
}
