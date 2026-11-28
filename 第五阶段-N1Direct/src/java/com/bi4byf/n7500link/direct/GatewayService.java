package com.bi4byf.n7500link.direct;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;

/** Foreground owner of the Bluetooth session and bounded low-risk web controls. */
public final class GatewayService extends Service {
    public static final String ACTION_START =
            GatewayContract.ACTION_START;
    public static final String ACTION_STOP =
            GatewayContract.ACTION_STOP;

    private static final String LOG_TAG = "N1Direct";
    private static final String CHANNEL_ID = "n1direct";
    private static final int NOTIFICATION_ID = 7500;
    private static final long BIND_RETRY_DELAY_MS = 3000L;

    private final LocalBinder binder = new LocalBinder();
    private SharedPreferences preferences;
    private StatusRepository repository;
    private SseHub sse;
    private AuthManager auth;
    private LocalWebServer webServer;
    private RadioSession radioSession;
    private AudioRoomService audioRoomService;
    private AudioLink audioLink;
    private PowerManager.WakeLock cpuWakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private WifiManager.WifiLock wifiLock;

    private volatile boolean running;
    private boolean radioStarted;
    private volatile String pendingToken;
    private volatile String currentToken;
    private volatile String bindAddress = "";
    private volatile String audioInitError;
    private volatile int audioStartGeneration;
    private volatile int port = GatewayContract.DEFAULT_PORT;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable bindRetry = () -> retryWebServerStart();

    public final class LocalBinder extends Binder {
        public GatewayService service() { return GatewayService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences(GatewayContract.PREFS, MODE_PRIVATE);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopGateway();
            stopSelf();
            return START_NOT_STICKY;
        }
        startGateway();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopGateway();
        super.onDestroy();
    }

    public synchronized void startGateway() {
        if (running) return;
        running = true;
        radioStarted = false;
        pendingToken = null;
        startForeground(NOTIFICATION_ID, buildNotification("正在启动直连控制"));
        acquireRuntimeLocks();

        repository = new StatusRepository((instanceId, revision, updatedAtMs, stale) -> {
            SseHub hub = sse;
            if (hub != null) hub.broadcastSnapshot(instanceId, revision, updatedAtMs, stale);
        });
        sse = new SseHub();
        repository.markStarting();
        GatewayConfig config = GatewayConfig.load(preferences);
        initializeAudioRoom();

        tryStartWebServer(config);
    }

    private void initializeAudioRoom() {
        audioInitError = null;
        try {
            audioRoomService = new AudioRoomService(
                    new File(getNoBackupFilesDir(), "audio"));
        } catch (IOException | RuntimeException e) {
            audioRoomService = null;
            audioInitError = "AUDIO_STORAGE_UNAVAILABLE";
            // Do not include a filesystem exception in logs or the web response.
            Log.w(LOG_TAG, "category=audio severity=WARN code=AUDIO_STORAGE_UNAVAILABLE");
        }
    }

    private synchronized void tryStartWebServer(GatewayConfig config) {
        if (!running || webServer != null) return;
        try {
            if (auth == null) {
                auth = new AuthManager(new PreferenceTokenStore(preferences),
                        System::currentTimeMillis);
                String recovered = preferences.getString(
                        GatewayContract.PREF_ACCESS_TOKEN_RECOVERY, null);
                boolean recoveredMatches = recovered != null && auth.matchesToken(recovered);
                if (recoveredMatches) {
                    currentToken = recovered;
                } else {
                    currentToken = null;
                    if (recovered != null) {
                        preferences.edit()
                                .remove(GatewayContract.PREF_ACCESS_TOKEN_RECOVERY)
                                .putBoolean(GatewayContract.PREF_TOKEN_RECOVERY_BOOTSTRAP, false)
                                .commit();
                    }
                }

                boolean hadToken = auth.hasToken();
                pendingToken = auth.initializeToken();
                if (pendingToken != null) rememberToken(pendingToken);
                if (!preferences.getBoolean(
                        GatewayContract.PREF_TOKEN_COMPACT_MIGRATED, false)) {
                    // Existing legacy releases used a long token. Rotate exactly once
                    // while moving to the compact token, invalidating old sessions.
                    if (hadToken) pendingToken = rotateAndRememberToken();
                    preferences.edit().putBoolean(
                            GatewayContract.PREF_TOKEN_COMPACT_MIGRATED, true).commit();
                }
                if (auth.hasToken() && currentToken == null) {
                    // A hash may exist without recoverable plaintext after an old
                    // upgrade or a damaged preference. Rotate once and retain it.
                    pendingToken = rotateAndRememberToken();
                }
                if (currentToken != null) {
                    preferences.edit().putBoolean(
                            GatewayContract.PREF_TOKEN_RECOVERY_BOOTSTRAP, true).commit();
                }
            }
            InetAddress address = GatewayConfig.resolveBindAddress(config.bindMode, preferences);
            bindAddress = address.getHostAddress();
            LocalWebServer.AssetSource assets = this::loadAsset;
            webServer = new LocalWebServer(address, GatewayContract.DEFAULT_PORT,
                    auth, repository, sse,
                    () -> radioSession == null ? null : radioSession.controlController(),
                    () -> radioSession == null ? null : radioSession.channelCatalog(),
                    () -> audioRoomService,
                    this::audioDiagnosticsJson,
                    new LocalWebServer.TokenControl() {
                        @Override public boolean setCustomToken(String rawToken) {
                            return setCustomAccessToken(rawToken);
                        }
                        @Override public int clearLoginRateLimit() {
                            return GatewayService.this.clearLoginRateLimit();
                        }
                    },
                    assets, this::audit);
            webServer.start();
            port = webServer.port();
        } catch (IOException e) {
            webServer = null;
            bindAddress = GatewayConfig.lastKnownAddress(preferences);
            repository.markFault(errorCode(e));
            updateNotification("等待网络就绪，稍后自动重试");
            mainHandler.removeCallbacks(bindRetry);
            mainHandler.postDelayed(bindRetry, BIND_RETRY_DELAY_MS);
            return;
        }

        startRadio(config);
    }

    private void retryWebServerStart() {
        synchronized (GatewayService.this) {
            if (!running || webServer != null) return;
            GatewayConfig config = GatewayConfig.load(preferences);
            tryStartWebServer(config);
        }
    }

    private synchronized void startRadio(GatewayConfig config) {
        if (!running || radioStarted) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            repository.markFault("BLUETOOTH_NOT_SUPPORTED");
            updateNotification("蓝牙不可用，配置页仍可使用");
            return;
        }
        if (config.deviceAddress == null || config.deviceAddress.trim().isEmpty()) {
            repository.markFault("DEVICE_NOT_SELECTED");
            updateNotification("尚未选择 N7500，请打开应用配置");
            return;
        }
        try {
            final BluetoothDevice device = adapter.getRemoteDevice(config.deviceAddress);
            radioSession = new RadioSession(adapter, device, repository,
                    new RadioSession.Listener() {
                        @Override public void onControlReady(int epoch) {
                            scheduleAudioStart(adapter, device, epoch);
                        }
                        @Override public void onControlLost(int epoch, String reasonCode) {
                            cancelAudioStart();
                        }
                    });
            radioSession.start();
            radioStarted = true;
            updateNotification("直连控制服务监听 " + bindAddress + ":" + port);
        } catch (IllegalArgumentException e) {
            repository.markFault("DEVICE_ADDRESS_INVALID");
            updateNotification("已选蓝牙设备无效，请重新选择");
        }
    }

    /**
     * Starts audio only after the control socket is online and its initial
     * discovery burst has had a short moment to settle. The original HT client
     * used this order; starting both RFCOMM sockets in parallel can make the
     * radio reject the second service lookup.
     */
    private void scheduleAudioStart(BluetoothAdapter adapter, BluetoothDevice device,
                                    int controlEpoch) {
        final int generation;
        synchronized (this) {
            generation = ++audioStartGeneration;
        }
        mainHandler.postDelayed(() -> {
            synchronized (GatewayService.this) {
                if (!running || generation != audioStartGeneration
                        || audioRoomService == null || radioSession == null
                        || !radioSession.isConnected()) return;
                startAudioLink(adapter, device);
            }
        }, 1200L);
    }

    private void cancelAudioStart() {
        int generation;
        synchronized (this) {
            generation = ++audioStartGeneration;
        }
        mainHandler.post(() -> {
            synchronized (GatewayService.this) {
                if (generation == audioStartGeneration) stopAudioLink();
            }
        });
    }

    private synchronized void startAudioLink(BluetoothAdapter adapter,
                                             BluetoothDevice device) {
        if (!running || audioRoomService == null) return;
        stopAudioLink();
        try {
            audioLink = new AudioLink(adapter, audioRoomService);
            audioLink.start(device);
        } catch (RuntimeException e) {
            audioLink = null;
            audioRoomService.markLinkState("DISCONNECTED", "AUDIO_LINK_START_FAILED");
            audit("audio", "WARN", "AUDIO_LINK_START_FAILED", null,
                    repository == null ? null : repository.instanceId(), null);
        }
    }

    private synchronized void stopAudioLink() {
        if (audioLink != null) {
            audioLink.stop();
            audioLink = null;
        }
    }

    public synchronized void stopGateway() {
        if (!running) return;
        running = false;
        mainHandler.removeCallbacks(bindRetry);
        releaseRuntimeLocks();
        if (webServer != null) webServer.stop();
        cancelAudioStart();
        stopAudioLink();
        if (audioRoomService != null) {
            audioRoomService.stop();
            audioRoomService = null;
        }
        if (radioSession != null) {
            radioSession.stop();
            radioSession = null;
        }
        if (repository != null) repository.markStopped();
        sse = null;
        auth = null;
        webServer = null;
        pendingToken = null;
        stopForeground(true);
    }

    public boolean isGatewayRunning() { return running; }
    public int gatewayPort() { return port; }
    public String gatewayBindAddress() { return bindAddress; }

    public String consumePendingToken() {
        synchronized (this) {
            String value = pendingToken;
            pendingToken = null;
            return value;
        }
    }

    public String rotateToken() {
        synchronized (this) {
            if (!running || auth == null) return null;
            pendingToken = rotateAndRememberToken();
            return pendingToken;
        }
    }

    public String currentToken() { return currentToken; }

    /**
     * Replaces the generated token with an operator-supplied 10..64 character
     * ASCII token. Success revokes every browser session. The raw value is kept
     * only in memory and in the existing private recovery preference.
     */
    public synchronized boolean setCustomAccessToken(String rawToken) {
        if (!running || auth == null) return false;
        String normalized = AuthManager.normalizeToken(rawToken);
        if (!auth.setCustomToken(normalized)) return false;
        pendingToken = normalized;
        rememberToken(normalized);
        return true;
    }

    /** Clears source-scoped failed-login windows without revoking valid sessions. */
    public synchronized int clearLoginRateLimit() {
        return auth == null ? -1 : auth.clearLoginRateLimit();
    }

    private String rotateAndRememberToken() {
        String rawToken = auth.rotateToken();
        rememberToken(rawToken);
        return rawToken;
    }

    private void rememberToken(String rawToken) {
        currentToken = rawToken;
        if (rawToken == null || rawToken.isEmpty()) return;
        // MODE_PRIVATE plus allowBackup=false keeps this local to this N1 install.
        // The raw value is never written to logs or HTTP responses.
        preferences.edit()
                .putString(GatewayContract.PREF_ACCESS_TOKEN_RECOVERY, rawToken)
                .putBoolean(GatewayContract.PREF_TOKEN_RECOVERY_BOOTSTRAP, true)
                .commit();
    }

    public String snapshotJson() {
        StatusRepository current = repository;
        if (current == null) {
            return "{\"ok\":false,\"code\":\"SERVICE_NOT_STARTED\"}";
        }
        return current.toJson(System.currentTimeMillis());
    }

    public String radioDiagnostics() {
        RadioSession current = radioSession;
        return current == null ? "未启动" : current.diagnosticCounters();
    }

    public String audioStatusSummary() {
        AudioRoomService rooms = audioRoomService;
        if (rooms == null) {
            return "音频不可用（" + (audioInitError == null
                    ? "尚未初始化" : audioInitError) + "）";
        }
        String state = rooms.audioState();
        String label;
        if ("RECEIVING".equals(state)) label = "正在接收音频";
        else if ("WAITING_PCM".equals(state)) label = "音频已连接，等待接收";
        else if ("CONNECTING".equals(state)) label = "音频正在连接";
        else if ("RECONNECTING".equals(state)) label = "音频正在重连";
        else if ("STOPPED".equals(state)) label = "音频已停止";
        else if ("CONNECTED".equals(state)) label = "音频已连接";
        else label = "音频未连接";
        String error = rooms.lastErrorCode();
        return "本地音频：" + label + (error == null ? "" : " · " + error);
    }

    /** Returns a secret-free snapshot suitable for the authenticated diagnostics route. */
    public String audioDiagnosticsJson() {
        AudioRoomService rooms = audioRoomService;
        if (rooms == null) {
            return "{\"ok\":false,\"state\":\"UNAVAILABLE\","
                    + "\"lastError\":\"AUDIO_INIT_FAILED\",\"lastPcmAt\":null,"
                    + "\"counters\":{\"linkStarted\":false}}";
        }
        AudioLink link = audioLink;
        if (link != null) return link.diagnosticsJson();
        return "{\"ok\":true"
                + ",\"state\":" + JsonUtil.quote(rooms.audioState())
                + ",\"lastError\":" + JsonUtil.nullableString(rooms.lastErrorCode())
                + ",\"lastPcmAt\":"
                + (rooms.lastPcmAtMs() <= 0L ? "null"
                        : JsonUtil.quote(GatewayContract.utc(rooms.lastPcmAtMs())))
                + ",\"counters\":{\"linkStarted\":false}}";
    }

    public String runtimeLocksStatus() {
        boolean cpuHeld = cpuWakeLock != null && cpuWakeLock.isHeld();
        boolean wifiHeld = wifiLock != null && wifiLock.isHeld();
        boolean screenHeld = screenWakeLock != null && screenWakeLock.isHeld();
        return "CPU=" + (cpuHeld ? "HELD" : "RELEASED")
                + " Wi-Fi=" + (wifiHeld ? "HELD" : "RELEASED")
                + " Screen=" + (screenHeld ? "HELD" : "RELEASED");
    }

    /** Applies optional runtime locks without restarting the Bluetooth session. */
    public synchronized void applyRuntimeOptions() {
        if (running) syncScreenWakeLock();
    }

    private String loadAsset(String name) throws IOException {
        AssetManager manager = getAssets();
        try (InputStream input = manager.open("web/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private void acquireRuntimeLocks() {
        if (cpuWakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            cpuWakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "N1Direct:radio");
            cpuWakeLock.setReferenceCounted(false);
        }
        if (!cpuWakeLock.isHeld()) cpuWakeLock.acquire();

        if (wifiLock == null) {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            wifiLock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "N1Direct:wifi");
            wifiLock.setReferenceCounted(false);
        }
        if (!wifiLock.isHeld()) wifiLock.acquire();
        syncScreenWakeLock();
    }

    private void syncScreenWakeLock() {
        boolean requested = GatewayConfig.screenAlwaysOn(preferences);
        if (requested && screenWakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            screenWakeLock = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "N1Direct:screen");
            screenWakeLock.setReferenceCounted(false);
        }
        if (requested && screenWakeLock != null && !screenWakeLock.isHeld()) {
            screenWakeLock.acquire();
        } else if (!requested && screenWakeLock != null) {
            if (screenWakeLock.isHeld()) screenWakeLock.release();
            screenWakeLock = null;
        }
    }

    private void releaseRuntimeLocks() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        screenWakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
        cpuWakeLock = null;
    }

    private void audit(String category, String severity, String code,
                       String sourceTag, String instanceId, String sessionHashPrefix) {
        String sourcePrefix = sourceTag == null || sourceTag.isEmpty() ? "none"
                : AuthManager.sha256Hex(sourceTag).substring(0, 8);
        String line = "category=" + category + " severity=" + severity
                + " code=" + code + " source=" + sourcePrefix
                + " instance=" + instanceId
                + " session=" + (sessionHashPrefix == null ? "none" : sessionHashPrefix);
        if ("WARN".equals(severity)) Log.w(LOG_TAG, line); else Log.i(LOG_TAG, line);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private static String errorCode(IOException error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? "GATEWAY_START_FAILED" : value;
    }

    private static final class PreferenceTokenStore implements AuthManager.TokenStore {
        private final SharedPreferences preferences;
        PreferenceTokenStore(SharedPreferences preferences) { this.preferences = preferences; }
        @Override public String loadHash() {
            return preferences.getString(GatewayContract.PREF_TOKEN_HASH, null);
        }
        @Override public long loadCreatedAt() {
            return preferences.getLong(GatewayContract.PREF_TOKEN_CREATED_AT, 0L);
        }
        @Override public void save(String hash, long createdAt) {
            preferences.edit().putString(GatewayContract.PREF_TOKEN_HASH, hash)
                    .putLong(GatewayContract.PREF_TOKEN_CREATED_AT, createdAt).apply();
        }
    }
}

