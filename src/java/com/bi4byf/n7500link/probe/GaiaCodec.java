package com.bi4byf.n7500link.probe;

import java.util.Arrays;
import java.util.Locale;

final class GaiaCodec {
    static final int GROUP_BASIC = 0x0002;

    static final int CMD_GET_DEV_INFO = 4;
    static final int CMD_REGISTER_NOTIFICATION = 6;
    static final int CMD_EVENT_NOTIFICATION = 9;
    static final int CMD_READ_SETTINGS = 10;
    static final int CMD_READ_RF_CH = 13;
    static final int CMD_GET_HT_STATUS = 20;
    static final int CMD_GET_VOLUME = 22;

    private byte[] buffer = new byte[256];
    private int length;
    private long rxChunks;
    private long rxBytes;
    private long rxFrames;
    private long discardedBytes;

    interface Listener {
        void onFrame(Frame frame);
        void onDiscarded(int byteCount);
    }

    static final class Frame {
        final byte[] raw;
        final byte[] body;
        final boolean checksumPresent;
        final int payloadLength;

        Frame(byte[] raw, byte[] body, boolean checksumPresent, int payloadLength) {
            this.raw = raw;
            this.body = body;
            this.checksumPresent = checksumPresent;
            this.payloadLength = payloadLength;
        }
    }

    void reset() {
        length = 0;
        rxChunks = 0;
        rxBytes = 0;
        rxFrames = 0;
        discardedBytes = 0;
    }

    void feed(byte[] chunk, Listener listener) {
        if (chunk == null || chunk.length == 0) return;
        rxChunks++;
        rxBytes += chunk.length;
        ensureCapacity(length + chunk.length);
        System.arraycopy(chunk, 0, buffer, length, chunk.length);
        length += chunk.length;

        int position = 0;
        while (position < length) {
            int header = findHeader(position);
            if (header < 0) {
                int keepFrom = position;
                if (length > position && buffer[length - 1] == (byte) 0xFF) {
                    keepFrom = length - 1;
                }
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
            boolean checksumPresent = (flags & 0x01) != 0;
            int totalLength = 8 + payloadLength + (checksumPresent ? 1 : 0);
            if (totalLength < 8) {
                position++;
                continue;
            }
            if (length - position < totalLength) break;

            byte[] raw = Arrays.copyOfRange(buffer, position, position + totalLength);
            int bodyLength = 4 + payloadLength;
            byte[] body = Arrays.copyOfRange(buffer, position + 4, position + 4 + bodyLength);
            rxFrames++;
            listener.onFrame(new Frame(raw, body, checksumPresent, payloadLength));
            position += totalLength;
        }

        if (position > 0) {
            int remaining = length - position;
            if (remaining > 0) {
                System.arraycopy(buffer, position, buffer, 0, remaining);
            }
            length = remaining;
        }
    }

    private int findHeader(int start) {
        for (int i = start; i + 1 < length; i++) {
            if (buffer[i] == (byte) 0xFF && buffer[i + 1] == (byte) 0x01) return i;
        }
        return -1;
    }

    private void ensureCapacity(int needed) {
        if (needed <= buffer.length) return;
        int next = buffer.length;
        while (next < needed) next *= 2;
        buffer = Arrays.copyOf(buffer, next);
    }

    static byte[] buildFrame(int cmd, byte[] payload) {
        int payloadLength = payload == null ? 0 : payload.length;
        byte[] body = new byte[4 + payloadLength];
        body[0] = 0x00;
        body[1] = 0x02;
        body[2] = (byte) ((cmd >> 8) & 0xFF);
        body[3] = (byte) (cmd & 0xFF);
        if (payloadLength > 0) System.arraycopy(payload, 0, body, 4, payloadLength);

        byte[] frame = new byte[8 + payloadLength];
        frame[0] = (byte) 0xFF;
        frame[1] = 0x01;
        frame[2] = 0x00;
        frame[3] = (byte) payloadLength;
        System.arraycopy(body, 0, frame, 4, body.length);
        return frame;
    }

    static int group(byte[] body) {
        return HexUtil.u16(body, 0);
    }

    static int rawCommand(byte[] body) {
        return HexUtil.u16(body, 2);
    }

    static int command(byte[] body) {
        return rawCommand(body) & 0x7FFF;
    }

    static boolean isResponse(byte[] body) {
        return (rawCommand(body) & 0x8000) != 0;
    }

    static int status(byte[] body) {
        return body != null && body.length > 4 ? (body[4] & 0xFF) : -1;
    }

    static byte[] payload(byte[] body) {
        if (body == null || body.length <= 4) return new byte[0];
        return Arrays.copyOfRange(body, 4, body.length);
    }

    static String commandName(int command) {
        switch (command) {
            case CMD_GET_DEV_INFO: return "GET_DEV_INFO";
            case 5: return "READ_STATUS";
            case CMD_REGISTER_NOTIFICATION: return "REGISTER_NOTIFICATION";
            case CMD_EVENT_NOTIFICATION: return "EVENT_NOTIFICATION";
            case CMD_READ_SETTINGS: return "READ_SETTINGS";
            case CMD_READ_RF_CH: return "READ_RF_CH";
            case CMD_GET_HT_STATUS: return "GET_HT_STATUS";
            case CMD_GET_VOLUME: return "GET_VOLUME";
            default: return "CMD_" + command;
        }
    }

    static String summary(byte[] body) {
        if (body == null || body.length < 4) return "帧体不足 4 字节";
        int group = group(body);
        int command = command(body);
        boolean response = isResponse(body);
        int status = status(body);
        return String.format(Locale.US, "GROUP=%d %s%s STATUS=%s PAYLOAD=%s",
                group,
                commandName(command),
                response ? " [响应]" : " [通知/请求]",
                status >= 0 ? String.valueOf(status) : "n/a",
                HexUtil.toHex(payload(body)));
    }

    long getRxChunks() { return rxChunks; }
    long getRxBytes() { return rxBytes; }
    long getRxFrames() { return rxFrames; }
    long getDiscardedBytes() { return discardedBytes; }
}
