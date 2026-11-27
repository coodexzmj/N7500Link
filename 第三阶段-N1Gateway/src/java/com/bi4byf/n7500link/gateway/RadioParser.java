package com.bi4byf.n7500link.gateway;

import java.io.UnsupportedEncodingException;

/** Parses only the six G1 read commands and their allowed notifications. */
public final class RadioParser {
    public static final int TYPE_OTHER = 0;
    public static final int TYPE_DEVICE = 1;
    public static final int TYPE_SETTINGS = 2;
    public static final int TYPE_STATUS = 3;
    public static final int TYPE_CHANNEL = 4;
    public static final int TYPE_VOLUME = 5;
    public static final int TYPE_NOTIFICATION = 6;

    private RadioParser() {}

    public static final class Parsed {
        public final int type;
        public final boolean success;
        public final String errorCode;
        public final int notification;
        public final RadioModels.DeviceInfo device;
        public final RadioModels.Settings settings;
        public final RadioModels.RadioStatus status;
        public final RadioModels.ChannelInfo channel;
        public final Integer volume;

        private Parsed(int type, boolean success, String errorCode, int notification,
                       RadioModels.DeviceInfo device, RadioModels.Settings settings,
                       RadioModels.RadioStatus status, RadioModels.ChannelInfo channel,
                       Integer volume) {
            this.type = type;
            this.success = success;
            this.errorCode = errorCode;
            this.notification = notification;
            this.device = device;
            this.settings = settings;
            this.status = status;
            this.channel = channel;
            this.volume = volume;
        }

        static Parsed error(int type, String code) {
            return new Parsed(type, false, code, -1, null, null, null, null, null);
        }
    }

    public static Parsed parseResponse(byte[] body) {
        if (body == null || body.length < 5) return Parsed.error(TYPE_OTHER, "FRAME_TOO_SHORT");
        int group = GaiaCodec.group(body);
        int command = GaiaCodec.command(body);
        int status = GaiaCodec.status(body);
        if (!GaiaCodec.isResponse(body) || group != GaiaCodec.GROUP_BASIC) {
            return Parsed.error(TYPE_OTHER, "UNEXPECTED_FRAME");
        }
        if (status != 0) return Parsed.error(typeForCommand(command), "RADIO_STATUS_" + status);

        switch (command) {
            case GaiaCodec.CMD_GET_DEV_INFO: return parseDevice(body);
            case GaiaCodec.CMD_READ_SETTINGS: return parseSettings(body);
            case GaiaCodec.CMD_GET_HT_STATUS: return parseStatus(body);
            case GaiaCodec.CMD_READ_RF_CH: return parseChannel(body);
            case GaiaCodec.CMD_GET_VOLUME: return parseVolume(body);
            case GaiaCodec.CMD_REGISTER_NOTIFICATION:
                return new Parsed(TYPE_OTHER, true, null, -1, null, null, null, null, null);
            default: return Parsed.error(TYPE_OTHER, "COMMAND_NOT_ALLOWED");
        }
    }

    public static Parsed parseNotification(byte[] body) {
        if (body == null || body.length < 5 || GaiaCodec.isResponse(body)
                || GaiaCodec.command(body) != GaiaCodec.CMD_EVENT_NOTIFICATION) {
            return Parsed.error(TYPE_OTHER, "NOT_A_NOTIFICATION");
        }
        int notification = HexUtil.u8(body, 4);
        if (notification == 1 && body.length >= 9) {
            Parsed status = parseStatus(body);
            return new Parsed(TYPE_STATUS, status.success, status.errorCode,
                    notification, null, null, status.status, null, null);
        }
        if (notification == 6 && body.length >= 17) {
            Parsed settings = parseSettings(body);
            return new Parsed(TYPE_SETTINGS, settings.success, settings.errorCode,
                    notification, null, settings.settings, null, null, null);
        }
        return new Parsed(TYPE_NOTIFICATION, true, null, notification,
                null, null, null, null, null);
    }

    public static boolean isNotification(byte[] body) {
        return body != null && body.length >= 4 && !GaiaCodec.isResponse(body)
                && GaiaCodec.command(body) == GaiaCodec.CMD_EVENT_NOTIFICATION;
    }

    private static int typeForCommand(int command) {
        switch (command) {
            case GaiaCodec.CMD_GET_DEV_INFO: return TYPE_DEVICE;
            case GaiaCodec.CMD_READ_SETTINGS: return TYPE_SETTINGS;
            case GaiaCodec.CMD_GET_HT_STATUS: return TYPE_STATUS;
            case GaiaCodec.CMD_READ_RF_CH: return TYPE_CHANNEL;
            case GaiaCodec.CMD_GET_VOLUME: return TYPE_VOLUME;
            default: return TYPE_OTHER;
        }
    }

    private static Parsed parseDevice(byte[] body) {
        if (body.length < 15) return Parsed.error(TYPE_DEVICE, "DEVICE_FRAME_TOO_SHORT");
        int vendorId = HexUtil.u8(body, 5);
        int productId = HexUtil.u16(body, 6);
        int hardwareVersion = HexUtil.u8(body, 8);
        int softwareVersion = HexUtil.u16(body, 9);
        int flags = HexUtil.u8(body, 11);
        int zoneCount = ((flags & 0x03) << 4) + ((HexUtil.u8(body, 12) & 0xF0) >> 4);
        int channelCount = HexUtil.u8(body, 13);
        int bandCount = (HexUtil.u8(body, 14) & 0xF0) >> 4;
        boolean fmReceive = (flags & 0x80) != 0;
        boolean dmr = (HexUtil.u8(body, 12) & 0x01) != 0;
        boolean vfo = (HexUtil.u8(body, 12) & 0x02) != 0;
        return new Parsed(TYPE_DEVICE, true, null, -1,
                new RadioModels.DeviceInfo(vendorId, productId, hardwareVersion,
                        softwareVersion, channelCount, zoneCount, bandCount,
                        fmReceive, dmr, vfo),
                null, null, null, null);
    }

    private static Parsed parseSettings(byte[] body) {
        if (body.length < 17) return Parsed.error(TYPE_SETTINGS, "SETTINGS_FRAME_TOO_SHORT");
        int channelA = ((HexUtil.u8(body, 5) & 0xF0) >> 4) + (HexUtil.u8(body, 14) & 0xF0);
        int channelB = (HexUtil.u8(body, 5) & 0x0F) + ((HexUtil.u8(body, 14) & 0x0F) << 4);
        int doubleChannel = (HexUtil.u8(body, 6) & 0x30) >> 4;
        boolean scan = (HexUtil.u8(body, 6) & 0x80) != 0;
        int squelch = HexUtil.u8(body, 6) & 0x0F;
        String side = doubleChannel == 1 ? "A" : (doubleChannel == 2 ? "B" : null);
        return new Parsed(TYPE_SETTINGS, true, null, -1, null,
                new RadioModels.Settings(channelA, channelB, side, scan, squelch, side),
                null, null, null);
    }

    private static Parsed parseStatus(byte[] body) {
        if (body.length < 9) return Parsed.error(TYPE_STATUS, "STATUS_FRAME_TOO_SHORT");
        int flags = HexUtil.u8(body, 5);
        int currentChannel = (((HexUtil.u8(body, 8) & 0x3C) >> 2) << 4)
                + ((HexUtil.u8(body, 6) & 0xF0) >> 4);
        int rssi = (HexUtil.u8(body, 7) & 0xF0) >> 4;
        int zone = ((HexUtil.u8(body, 7) & 0x0F) << 2)
                + ((HexUtil.u8(body, 8) & 0xC0) >> 6);
        return new Parsed(TYPE_STATUS, true, null, -1, null, null,
                new RadioModels.RadioStatus(
                        (flags & 0x80) != 0,
                        (flags & 0x40) != 0,
                        (flags & 0x20) != 0,
                        (flags & 0x10) != 0,
                        (flags & 0x02) != 0,
                        (flags & 0x01) != 0,
                        currentChannel, zone, rssi),
                null, null);
    }

    private static Parsed parseChannel(byte[] body) {
        if (body.length < 30) return Parsed.error(TYPE_CHANNEL, "CHANNEL_FRAME_TOO_SHORT");
        int channelId = HexUtil.u8(body, 5);
        int txFreq = HexUtil.s32(body, 6) & 0x3FFFFFFF;
        int rxFreq = HexUtil.s32(body, 10) & 0x3FFFFFFF;
        String name = decodeGbk(body, 20, Math.min(10, body.length - 20));
        return new Parsed(TYPE_CHANNEL, true, null, -1, null, null, null,
                new RadioModels.ChannelInfo(channelId, name.isEmpty() ? null : name,
                        rxFreq, txFreq), null);
    }

    private static Parsed parseVolume(byte[] body) {
        if (body.length < 6) return Parsed.error(TYPE_VOLUME, "VOLUME_FRAME_TOO_SHORT");
        int volume = HexUtil.u8(body, 5);
        if (volume < 0 || volume > 15) return Parsed.error(TYPE_VOLUME, "VOLUME_OUT_OF_RANGE");
        return new Parsed(TYPE_VOLUME, true, null, -1, null, null, null, null, volume);
    }

    private static String decodeGbk(byte[] data, int offset, int length) {
        if (length <= 0) return "";
        int end = offset + length;
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
