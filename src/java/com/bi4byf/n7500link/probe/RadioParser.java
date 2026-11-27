package com.bi4byf.n7500link.probe;

import java.io.UnsupportedEncodingException;
import java.util.Locale;

final class RadioParser {
    static final int TYPE_OTHER = 0;
    static final int TYPE_DEV_INFO = 1;
    static final int TYPE_SETTINGS = 2;
    static final int TYPE_HT_STATUS = 3;
    static final int TYPE_CHANNEL = 4;
    static final int TYPE_VOLUME = 5;
    static final int TYPE_NOTIFICATION = 6;

    static final class Result {
        final int type;
        final boolean success;
        final String details;
        final int suggestedChannel;
        final String shortStatus;

        Result(int type, boolean success, String details, int suggestedChannel, String shortStatus) {
            this.type = type;
            this.success = success;
            this.details = details;
            this.suggestedChannel = suggestedChannel;
            this.shortStatus = shortStatus;
        }

        static Result other(String details) {
            return new Result(TYPE_OTHER, true, details, -1, null);
        }
    }

    private RadioParser() {}

    static Result parse(byte[] body) {
        if (body == null || body.length < 4) {
            return new Result(TYPE_OTHER, false, "帧体不足，无法解析。", -1, null);
        }
        int group = GaiaCodec.group(body);
        int cmd = GaiaCodec.command(body);
        boolean response = GaiaCodec.isResponse(body);
        int status = GaiaCodec.status(body);

        if (!response) {
            if (cmd == GaiaCodec.CMD_EVENT_NOTIFICATION && body.length >= 5) {
                return parseNotification(body);
            }
            return Result.other("非响应帧：" + GaiaCodec.summary(body));
        }
        if (status != 0) {
            return new Result(TYPE_OTHER, false,
                    String.format(Locale.US, "命令 %s 返回失败状态 %d，未解析业务数据。原始帧体：%s",
                            GaiaCodec.commandName(cmd), status, HexUtil.toHex(body)), -1, null);
        }

        if (group != GaiaCodec.GROUP_BASIC) {
            return Result.other("非基础指令组：" + GaiaCodec.summary(body));
        }

        switch (cmd) {
            case GaiaCodec.CMD_GET_DEV_INFO:
                return parseDevInfo(body);
            case GaiaCodec.CMD_READ_SETTINGS:
                return parseSettings(body);
            case GaiaCodec.CMD_GET_HT_STATUS:
                return parseHtStatus(body);
            case GaiaCodec.CMD_READ_RF_CH:
                return parseChannel(body);
            case GaiaCodec.CMD_GET_VOLUME:
                return parseVolume(body);
            default:
                return Result.other("解析器暂未覆盖该命令：" + GaiaCodec.summary(body));
        }
    }

    private static Result parseDevInfo(byte[] body) {
        if (body.length < 15) {
            return new Result(TYPE_DEV_INFO, false, "GET_DEV_INFO 帧过短（" + body.length + " 字节）。", -1, null);
        }
        int vendorId = HexUtil.u8(body, 5);
        int productId = HexUtil.u16(body, 6);
        int hwVer = HexUtil.u8(body, 8);
        int softVer = HexUtil.u16(body, 9);
        int flags = HexUtil.u8(body, 11);
        int regionCount = ((flags & 0x03) << 4) + ((HexUtil.u8(body, 12) & 0xF0) >> 4);
        int channelCount = HexUtil.u8(body, 13);
        int freqRangeCount = (HexUtil.u8(body, 14) & 0xF0) >> 4;
        boolean supportRadio = (flags & 0x80) != 0;
        boolean supportDmr = (HexUtil.u8(body, 12) & 0x01) != 0;
        boolean supportVfo = (HexUtil.u8(body, 12) & 0x02) != 0;

        String details = String.format(Locale.US,
                "设备：%s\n厂商ID：%d\n产品ID：0x%04X\n硬件版本：0x%02X\n软件版本：0x%04X\n" +
                "信道数：%d\n区域数：%d\n频段段数：%d\n支持FM广播接收：%s\n支持DMR：%s\n支持VFO：%s",
                productName(productId), vendorId, productId, hwVer, softVer,
                channelCount, regionCount, freqRangeCount, yesNo(supportRadio), yesNo(supportDmr), yesNo(supportVfo));
        return new Result(TYPE_DEV_INFO, true, details, -1,
                productName(productId) + " / " + channelCount + "CH");
    }

    private static Result parseSettings(byte[] body) {
        if (body.length < 17) {
            return new Result(TYPE_SETTINGS, false, "READ_SETTINGS 帧过短（" + body.length + " 字节）。", -1, null);
        }
        int channelA = ((HexUtil.u8(body, 5) & 0xF0) >> 4) + (HexUtil.u8(body, 14) & 0xF0);
        int channelB = (HexUtil.u8(body, 5) & 0x0F) + ((HexUtil.u8(body, 14) & 0x0F) << 4);
        int doubleChannel = (HexUtil.u8(body, 6) & 0x30) >> 4;
        boolean scan = (HexUtil.u8(body, 6) & 0x80) != 0;
        int squelch = HexUtil.u8(body, 6) & 0x0F;
        int suggested = doubleChannel == 2 ? channelB : channelA;
        String selected = new String[] {"关闭", "信道A", "信道B", "VFO C"}[Math.min(doubleChannel, 3)];

        String details = String.format(Locale.US,
                "信道A：%d\n信道B：%d\n当前选择：%s\n扫描：%s\n静噪：%d\n建议读取信道索引：%d",
                channelA, channelB, selected, yesNo(scan), squelch, suggested);
        return new Result(TYPE_SETTINGS, true, details, suggested,
                "CH-A=" + channelA + " CH-B=" + channelB);
    }

    private static Result parseHtStatus(byte[] body) {
        if (body.length < 9) {
            return new Result(TYPE_HT_STATUS, false, "GET_HT_STATUS 帧过短（" + body.length + " 字节）。", -1, null);
        }
        int flags = HexUtil.u8(body, 5);
        boolean powerOn = (flags & 0x80) != 0;
        boolean tx = (flags & 0x40) != 0;
        boolean sq = (flags & 0x20) != 0;
        boolean rx = (flags & 0x10) != 0;
        int channelMode = (flags & 0x0C) >> 2;
        boolean scan = (flags & 0x02) != 0;
        boolean fmBroadcast = (flags & 0x01) != 0;
        int currentChannel = (((HexUtil.u8(body, 8) & 0x3C) >> 2) << 4)
                + ((HexUtil.u8(body, 6) & 0xF0) >> 4);
        int rssi = (HexUtil.u8(body, 7) & 0xF0) >> 4;
        int region = ((HexUtil.u8(body, 7) & 0x0F) << 2) + ((HexUtil.u8(body, 8) & 0xC0) >> 6);
        String[] modes = {"关闭", "A", "B", "C"};

        String details = String.format(Locale.US,
                "电源：%s\n发射：%s\n静噪开启：%s\n接收：%s\n双信道模式：%s\n扫描：%s\nFM广播：%s\n" +
                "当前信道：%d\nRSSI档位：%d\n当前区域：%d",
                yesNo(powerOn), yesNo(tx), yesNo(sq), yesNo(rx), modes[Math.min(channelMode, 3)],
                yesNo(scan), yesNo(fmBroadcast), currentChannel, rssi, region);
        return new Result(TYPE_HT_STATUS, true, details, -1,
                (powerOn ? "开机" : "关机") + " / " + (tx ? "TX" : (rx ? "RX" : "待机"))
                        + " / CH " + currentChannel + " / RSSI " + rssi);
    }

    private static Result parseChannel(byte[] body) {
        if (body.length < 30) {
            return new Result(TYPE_CHANNEL, false, "READ_RF_CH 帧过短（" + body.length + " 字节）。", -1, null);
        }
        int channelId = HexUtil.u8(body, 5);
        int txMod = HexUtil.u8(body, 6) >> 6;
        int txFreq = HexUtil.s32(body, 6) & 0x3FFFFFFF;
        int rxMod = HexUtil.u8(body, 10) >> 6;
        int rxFreq = HexUtil.s32(body, 10) & 0x3FFFFFFF;
        int txSubAudio = HexUtil.u16(body, 14);
        int rxSubAudio = HexUtil.u16(body, 16);
        int flags = HexUtil.u8(body, 18);
        boolean scan = (flags & 0x80) != 0;
        boolean highPower = (flags & 0x40) != 0;
        boolean talkAround = (flags & 0x20) != 0;
        boolean wide = (flags & 0x10) != 0;
        boolean txDisabled = (flags & 0x01) != 0;
        String name = decodeGbk(body, 20, Math.min(10, body.length - 20));

        String details = String.format(Locale.US,
                "信道ID：%d\n名称：%s\n接收：%s (%s)\n发射：%s (%s)\n" +
                "TX亚音：%d\nRX亚音：%d\n带宽：%s\n扫描：%s\n高功率：%s\nTalk-around：%s\n发射禁用：%s",
                channelId, name.isEmpty() ? "(空)" : name,
                HexUtil.frequencyMHz(rxFreq), modulationName(rxMod),
                HexUtil.frequencyMHz(txFreq), modulationName(txMod),
                txSubAudio, rxSubAudio, wide ? "宽带" : "窄带",
                yesNo(scan), yesNo(highPower), yesNo(talkAround), yesNo(txDisabled));
        return new Result(TYPE_CHANNEL, true, details, -1,
                "CH " + channelId + " / " + HexUtil.frequencyMHz(rxFreq));
    }

    private static Result parseVolume(byte[] body) {
        if (body.length < 6) {
            return new Result(TYPE_VOLUME, false, "GET_VOLUME 帧过短（" + body.length + " 字节）。", -1, null);
        }
        int volume = HexUtil.u8(body, 5);
        String details = "音量：" + volume + " / 15";
        return new Result(TYPE_VOLUME, true, details, -1, "VOL " + volume);
    }

    private static Result parseNotification(byte[] body) {
        int notification = HexUtil.u8(body, 4);
        String name;
        switch (notification) {
            case 1: {
                Result status = parseHtStatus(body);
                return new Result(TYPE_NOTIFICATION, status.success,
                        "通知：HT_STATUS_CHANGED\n\n" + status.details,
                        -1, status.shortStatus);
            }
            case 2: name = "DATA_RXD"; break;
            case 3: name = "NEW_INQUIRY_DATA"; break;
            case 4: name = "RESTORE_FACTORY_SETTINGS"; break;
            case 5: name = "HT_CH_CHANGED"; break;
            case 6: name = "HT_SETTINGS_CHANGED"; break;
            case 7: name = "RINGING_STOPPED"; break;
            case 8: name = "RADIO_STATUS_CHANGED"; break;
            case 9: name = "USER_ACTION"; break;
            case 10: name = "SYSTEM_EVENT"; break;
            case 11: name = "BSS_SETTINGS_CHANGED"; break;
            case 12: name = "DATA_TXD"; break;
            case 13: name = "POSITION_CHANGE"; break;
            case 14: name = "FREQ_MODE_STATUS_CHANGED"; break;
            default: name = "UNKNOWN_NOTIFICATION_" + notification; break;
        }
        if (notification == 6 && body.length >= 17) {
            Result settings = parseSettings(body);
            return new Result(TYPE_NOTIFICATION, settings.success,
                    "通知：" + name + "\n\n" + settings.details,
                    settings.suggestedChannel, settings.shortStatus);
        }
        if (notification == 14 && body.length >= 9) {
            int rxFreq = HexUtil.s32(body, 5) & 0x3FFFFFFF;
            return new Result(TYPE_NOTIFICATION, true,
                    "通知：" + name + "\nVFO接收频率：" + HexUtil.frequencyMHz(rxFreq),
                    -1, "VFO " + HexUtil.frequencyMHz(rxFreq));
        }
        return new Result(TYPE_NOTIFICATION, true,
                "通知：" + name + "\n原始帧体：" + HexUtil.toHex(body),
                -1, name);
    }

    private static String productName(int productId) {
        switch (productId) {
            case 0x0001: return "VR-N7500";
            case 0x0002: return "VR-N76";
            case 0x0003: return "SA-888S";
            case 0x0004: return "HG-UV98";
            case 0x0005: return "HAM-AIO";
            default: return "未知产品(0x" + String.format(Locale.US, "%04X", productId) + ")";
        }
    }

    private static String modulationName(int mod) {
        switch (mod) {
            case 0: return "FM";
            case 1: return "AM";
            case 2: return "DMR";
            default: return "保留";
        }
    }

    private static String yesNo(boolean value) { return value ? "是" : "否"; }

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
