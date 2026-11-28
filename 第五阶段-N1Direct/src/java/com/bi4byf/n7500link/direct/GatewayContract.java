package com.bi4byf.n7500link.direct;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public final class GatewayContract {
    private GatewayContract() {}

    public static final String APP_NAME = "N1Direct";
    public static final String PACKAGE_NAME = "com.bi4byf.n7500link.direct";
    public static final String VERSION = "0.8.0";
    public static final String ACTION_START =
            "com.bi4byf.n7500link.direct.action.START";
    public static final String ACTION_STOP =
            "com.bi4byf.n7500link.direct.action.STOP";
    public static final String ACTION_BRIDGE_WAKE =
            "com.bi4byf.n7500link.direct.action.BRIDGE_WAKE";
    public static final int SCHEMA_VERSION = 1;
    public static final int DEFAULT_PORT = 8710;
    public static final long STALE_AFTER_MS = 15000L;
    public static final long SESSION_MAX_AGE_MS = 12L * 60L * 60L * 1000L;
    public static final int MAX_SESSIONS = 8;
    public static final int ACCESS_TOKEN_LENGTH = 10;
    public static final int ACCESS_TOKEN_MIN_LENGTH = 10;
    public static final int ACCESS_TOKEN_MAX_LENGTH = 64;

    public static final String PREFS = "n7500_gateway";
    public static final String PREF_TOKEN_HASH = "token_hash";
    public static final String PREF_TOKEN_CREATED_AT = "token_created_at";
    public static final String PREF_ACCESS_TOKEN_RECOVERY = "token_recovery_v1";
    public static final String PREF_TOKEN_RECOVERY_BOOTSTRAP = "token_recovery_bootstrap_v1";
    public static final String PREF_TOKEN_COMPACT_MIGRATED =
            "token_compact_length_v1";
    public static final String PREF_DEVICE_ADDRESS = "device_address";
    public static final String PREF_DEVICE_LABEL = "device_label";
    public static final String PREF_BIND_MODE = "bind_mode";
    public static final String PREF_BIND_ADDRESS = "bind_address";
    public static final String PREF_BIND_MODE_WIFI_DEFAULT_MIGRATED =
            "bind_mode_wifi_default_v1";
    public static final String PREF_BOOT_EXTERNAL = "boot_external";
    public static final String PREF_BOOT_ENABLED = "boot_enabled";
    public static final String PREF_SCREEN_ALWAYS_ON = "screen_always_on";
    public static final String PREF_SCREEN_DEFAULT_MIGRATED =
            "screen_always_on_default_v2";
    public static final String PREF_SCREEN_INITIALIZATION_FIX_MIGRATED =
            "screen_always_on_init_fix_v3";
    public static final boolean DEFAULT_SCREEN_ALWAYS_ON = true;
    public static final String PREF_BOOT_LAST_AT = "boot_last_at";
    public static final String PREF_BOOT_LAST_ACTION = "boot_last_action";
    public static final String PREF_BOOT_LAST_RESULT = "boot_last_result";
    public static final String PREF_BOOT_JOB_LAST_AT = "boot_job_last_at";
    public static final String PREF_BOOT_JOB_LAST_ID = "boot_job_last_id";
    public static final String PREF_BOOT_JOB_LAST_RESULT = "boot_job_last_result";

    public static final String MODE_LOOPBACK = "LOOPBACK";
    public static final String MODE_WIFI = "WIFI";
    public static final String MODE_TAILSCALE = "TAILSCALE";

    public static final String GW_STOPPED = "STOPPED";
    public static final String GW_STARTING = "STARTING";
    public static final String GW_CONNECTING_BLUETOOTH = "CONNECTING_BLUETOOTH";
    public static final String GW_ONLINE = "ONLINE";
    public static final String GW_DEGRADED = "DEGRADED";
    public static final String GW_RECONNECTING = "RECONNECTING";
    public static final String GW_FAULT = "FAULT";

    public static final String CONN_DISCONNECTED = "DISCONNECTED";
    public static final String CONN_CONNECTING = "CONNECTING";
    public static final String CONN_CONNECTED = "CONNECTED";
    public static final String CONN_RECONNECTING = "RECONNECTING";
    public static final String CONN_FAULT = "FAULT";

    public static final String SOURCE_NONE = "none";
    public static final String SOURCE_INITIAL = "initial";
    public static final String SOURCE_POLL = "poll";
    public static final String SOURCE_NOTIFICATION = "notification";

    public static String newInstanceId() {
        return "gw-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static String utcNow() {
        return utc(System.currentTimeMillis());
    }

    public static String utc(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMs));
    }
}


