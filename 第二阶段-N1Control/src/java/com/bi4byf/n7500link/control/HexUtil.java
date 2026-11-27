package com.bi4byf.n7500link.control;

import java.util.Locale;

final class HexUtil {
    private HexUtil() {}

    static String toHex(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder out = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) out.append(' ');
            int value = data[i] & 0xFF;
            out.append(Character.toUpperCase(Character.forDigit(value >> 4, 16)));
            out.append(Character.toUpperCase(Character.forDigit(value & 0x0F, 16)));
        }
        return out.toString();
    }

    static String toCompactHex(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int value = b & 0xFF;
            out.append(Character.toUpperCase(Character.forDigit(value >> 4, 16)));
            out.append(Character.toUpperCase(Character.forDigit(value & 0x0F, 16)));
        }
        return out.toString();
    }

    static int u8(byte[] data, int index) {
        if (data == null || index < 0 || index >= data.length) return 0;
        return data[index] & 0xFF;
    }

    static int u16(byte[] data, int index) {
        if (data == null || index < 0 || index + 1 >= data.length) return 0;
        return ((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF);
    }

    static int s32(byte[] data, int index) {
        if (data == null || index < 0 || index + 3 >= data.length) return 0;
        return ((data[index] & 0xFF) << 24)
                | ((data[index + 1] & 0xFF) << 16)
                | ((data[index + 2] & 0xFF) << 8)
                | (data[index + 3] & 0xFF);
    }

    static String frequencyMHz(int hz) {
        return String.format(Locale.US, "%.4f MHz", hz / 1_000_000.0);
    }

    static String signedHex16(int value) {
        return String.format(Locale.US, "0x%04X", value & 0xFFFF);
    }
}

