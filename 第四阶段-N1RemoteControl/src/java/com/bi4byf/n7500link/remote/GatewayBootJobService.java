package com.bi4byf.n7500link.remote;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/** JobScheduler fallback used after reboot or if the foreground service is lost. */
public final class GatewayBootJobService extends JobService {
    private static final String LOG_TAG = "N1GatewayBoot";

    @Override public boolean onStartJob(JobParameters parameters) {
        Context context = getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(
                GatewayContract.PREFS, Context.MODE_PRIVATE);
        preferences.edit()
                .putLong(GatewayContract.PREF_BOOT_JOB_LAST_AT, System.currentTimeMillis())
                .putInt(GatewayContract.PREF_BOOT_JOB_LAST_ID, parameters.getJobId())
                .apply();

        if (!GatewayBootScheduler.enabled(context)) {
            preferences.edit()
                    .putString(GatewayContract.PREF_BOOT_JOB_LAST_RESULT, "SKIPPED_DISABLED")
                    .apply();
            jobFinished(parameters, false);
            return false;
        }
        try {
            startService(new Intent(context, GatewayService.class)
                    .setAction(GatewayService.ACTION_START));
            preferences.edit()
                    .putString(GatewayContract.PREF_BOOT_JOB_LAST_RESULT, "JOB_STARTED")
                    .apply();
            Log.i(LOG_TAG, "job " + parameters.getJobId() + " started gateway service");
        } catch (RuntimeException error) {
            String reason = error.getClass().getSimpleName();
            preferences.edit()
                    .putString(GatewayContract.PREF_BOOT_JOB_LAST_RESULT,
                            "JOB_START_REJECTED:" + reason)
                    .apply();
            Log.w(LOG_TAG, "job failed to start gateway service", error);
        }
        jobFinished(parameters, false);
        return false;
    }

    @Override public boolean onStopJob(JobParameters parameters) { return false; }
}
