package com.bi4byf.n7500link.direct;

public final class JsonUtil {
    private JsonUtil() {}

    public static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    public static String nullableString(String value) {
        return value == null ? "null" : quote(value);
    }

    public static String nullableInt(Integer value) {
        return value == null ? "null" : Integer.toString(value);
    }

    public static String nullableLong(Long value) {
        return value == null ? "null" : Long.toString(value);
    }

    public static String nullableBoolean(Boolean value) {
        return value == null ? "null" : Boolean.toString(value);
    }

    public static String nullableDouble(Integer hz) {
        return hz == null ? "null" : String.format(java.util.Locale.US, "%.6f", hz / 1_000_000.0);
    }
}
