package com.bi4byf.n7500link.gateway;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** Persisted boot and recovery jobs. They contain no secret and are gated by local opt-in. */
final class GatewayBootScheduler {
    private static final String LOG_TAG = "N1GatewayBoot";
    private static final int JOB_IMMEDIATE = 7501;
    private static final int JOB_PERIODIC = 7502;

    private GatewayBootScheduler() {}

    static boolean enabled(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                GatewayContract.PREFS, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(GatewayContract.PREF_BOOT_ENABLED, false)) return false;
        String address = preferences.getString(GatewayContract.PREF_DEVICE_ADDRESS, null);
        return address != null && !address.trim().isEmpty();
    }

    static void schedule(Context context) {
        if (!enabled(context)) {
            cancel(context);
            return;
        }
        JobScheduler scheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        ComponentName service = new ComponentName(context, GatewayBootJobService.class);
        try {
            JobInfo immediate = new JobInfo.Builder(JOB_IMMEDIATE, service)
                    .setMinimumLatency(1000L)
                    .setOverrideDeadline(15000L)
                    .setPersisted(true)
                    .build();
            JobInfo periodic = new JobInfo.Builder(JOB_PERIODIC, service)
                    .setPeriodic(15L * 60L * 1000L)
                    .setPersisted(true)
                    .build();
            if (scheduler.getPendingJob(JOB_IMMEDIATE) == null) {
                scheduler.schedule(immediate);
            }
            if (scheduler.getPendingJob(JOB_PERIODIC) == null) {
                scheduler.schedule(periodic);
            }
            Log.i(LOG_TAG, "persisted boot jobs ensured");
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "failed to schedule boot jobs", error);
        }
    }

    static void cancel(Context context) {
        JobScheduler scheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        scheduler.cancel(JOB_IMMEDIATE);
        scheduler.cancel(JOB_PERIODIC);
        Log.i(LOG_TAG, "persisted boot jobs cancelled");
    }
}
