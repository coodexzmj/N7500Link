package com.bi4byf.n7500link.direct;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Boot entry point. Every outcome is persisted so a headless/real reboot can be
 * diagnosed without opening the local activity. JobScheduler is scheduled even
 * when the direct background service start is rejected by the OEM.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "N1GatewayBoot";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !GatewayContract.ACTION_BRIDGE_WAKE.equals(action)) {
            return;
        }

        SharedPreferences preferences = context.getSharedPreferences(
                GatewayContract.PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        preferences.edit()
                .putLong(GatewayContract.PREF_BOOT_LAST_AT, now)
                .putString(GatewayContract.PREF_BOOT_LAST_ACTION, action)
                .apply();

        if (!preferences.getBoolean(GatewayContract.PREF_BOOT_ENABLED, false)) {
            record(preferences, "SKIPPED_BOOT_DISABLED");
            Log.i(LOG_TAG, "boot skipped: auto-start is disabled");
            return;
        }
        String address = preferences.getString(GatewayContract.PREF_DEVICE_ADDRESS, null);
        if (address == null || address.trim().isEmpty()) {
            record(preferences, "SKIPPED_NO_DEVICE");
            Log.i(LOG_TAG, "boot skipped: no saved radio");
            return;
        }

        GatewayBootScheduler.schedule(context);
        Intent service = new Intent(context, GatewayService.class)
                .setAction(GatewayService.ACTION_START);
        try {
            context.startService(service);
            record(preferences, "RECEIVER_STARTED");
            Log.i(LOG_TAG, "boot receiver started gateway service");
        } catch (RuntimeException error) {
            String reason = error.getClass().getSimpleName();
            record(preferences, "RECEIVER_START_REJECTED:" + reason);
            Log.w(LOG_TAG, "direct service start rejected; persisted job remains", error);
        }
    }

    private static void record(SharedPreferences preferences, String result) {
        preferences.edit().putString(GatewayContract.PREF_BOOT_LAST_RESULT, result).apply();
    }
}
