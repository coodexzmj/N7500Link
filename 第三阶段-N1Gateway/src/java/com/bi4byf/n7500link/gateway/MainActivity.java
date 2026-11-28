package com.bi4byf.n7500link.gateway;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Set;

/** Local landscape control panel. It never owns the Bluetooth socket. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 20;
    private static final int REQUEST_ENABLE_BT = 21;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<DeviceItem> devices = new ArrayList<>();

    private SharedPreferences preferences;
    private BluetoothAdapter adapter;
    private ArrayAdapter<DeviceItem> deviceAdapter;
    private Spinner deviceSpinner;
    private Spinner modeSpinner;
    private Switch bootSwitch;
    private Switch foregroundSwitch;
    private Switch screenSwitch;
    private TextView statusView;
    private TextView addressView;
    private TextView tokenView;
    private TextView headerStatusView;
    private TextView headerDotView;
    private PositionStableScrollView mainScroll;
    private Button startButton;
    private Button stopButton;
    private Button rotateButton;
    private Button copyTokenButton;
    private Button openPageButton;
    private GatewayService gateway;
    private boolean bound;
    private boolean runtimeOptionsReady;
    private String displayedToken;

    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            renderStatus();
            ui.postDelayed(this, 1000L);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            gateway = ((GatewayService.LocalBinder) service).service();
            bound = true;
            String newToken = gateway.consumePendingToken();
            if (newToken != null) showToken(newToken);
            renderStatus();
            ui.removeCallbacks(statusPoll);
            ui.post(statusPoll);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            gateway = null;
            bound = false;
            renderStatus();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(GatewayContract.PREFS, MODE_PRIVATE);
        adapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        loadSavedSelection();
        bindRuntimeOptionListeners();
        refreshDevices();
        GatewayBootScheduler.schedule(this);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!bound) {
            bindService(new Intent(this, GatewayService.class), connection, BIND_AUTO_CREATE);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(statusPoll);
        ui.post(statusPoll);
        if (adapter != null && !adapter.isEnabled()) {
            Intent enable = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enable, REQUEST_ENABLE_BT);
        }
        requestRequiredPermissions();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(statusPoll);
        super.onPause();
    }

    @Override protected void onStop() {
        ui.removeCallbacks(statusPoll);
        if (bound) {
            unbindService(connection);
            bound = false;
            gateway = null;
        }
        super.onStop();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                   int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            refreshDevices();
            if (!hasLocationPermission()) {
                toast("未授予定位权限，Android 7.1 无法读取已配对蓝牙设备");
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) refreshDevices();
    }

    /** N1 boxes usually have a pointer or remote, not a touchscreen. */
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL && mainScroll != null) {
            float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (delta != 0f) {
                mainScroll.scrollByUser(delta < 0f ? dp(120) : -dp(120));
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /**
     * D-pad focus navigation is intentional: release the user anchor first so
     * the focused field can be brought into view instead of fighting the restore.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && mainScroll != null) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_PAGE_UP
                    || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                    || keyCode == KeyEvent.KEYCODE_MOVE_HOME
                    || keyCode == KeyEvent.KEYCODE_MOVE_END
                    || keyCode == KeyEvent.KEYCODE_TAB) {
                mainScroll.releaseAnchorForFocusNavigation();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void buildUi() {
        LinearLayout window = new LinearLayout(this);
        window.setOrientation(LinearLayout.VERTICAL);
        window.setBackgroundColor(Color.rgb(237, 237, 237));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(10), dp(18), dp(10));
        header.setBackgroundColor(Color.rgb(7, 193, 96));

        TextView mark = new TextView(this);
        mark.setText("N");
        mark.setTextSize(16);
        mark.setTextColor(Color.WHITE);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(Color.rgb(47, 209, 122), 9, 0));

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setPadding(dp(11), 0, dp(10), 0);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(mark, new LinearLayout.LayoutParams(dp(34), dp(34)));
        header.addView(titleColumn, titleParams);

        TextView title = new TextView(this);
        title.setText("N7500 网关");
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleColumn.addView(title);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(2), 0, 0);
        headerDotView = new TextView(this);
        headerDotView.setText("●");
        headerDotView.setTextSize(9);
        headerDotView.setTextColor(Color.rgb(217, 235, 225));
        statusRow.addView(headerDotView, new LinearLayout.LayoutParams(dp(12), dp(16)));
        headerStatusView = new TextView(this);
        headerStatusView.setText("只读监控 · 正在初始化");
        headerStatusView.setTextSize(11);
        headerStatusView.setTextColor(Color.rgb(224, 247, 235));
        headerStatusView.setPadding(dp(2), 0, 0, 0);
        statusRow.addView(headerStatusView);
        titleColumn.addView(statusRow);

        LinearLayout scrollTools = new LinearLayout(this);
        scrollTools.setOrientation(LinearLayout.HORIZONTAL);
        scrollTools.setGravity(Gravity.CENTER_VERTICAL);
        Button scrollUp = makeToolbarButton("上翻");
        Button scrollDown = makeToolbarButton("下翻");
        Button scrollBottom = makeToolbarButton("到底");
        scrollTools.addView(scrollUp);
        scrollTools.addView(scrollDown);
        scrollTools.addView(scrollBottom);
        header.addView(scrollTools);

        TextView readonly = new TextView(this);
        readonly.setText("只读");
        readonly.setTextSize(11);
        readonly.setTextColor(Color.WHITE);
        readonly.setGravity(Gravity.CENTER);
        readonly.setPadding(dp(9), dp(5), dp(9), dp(5));
        readonly.setBackground(rounded(0x22FFFFFF, 5, 0x66FFFFFF));
        header.addView(readonly);
        window.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mainScroll = new PositionStableScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setBackgroundColor(Color.rgb(237, 237, 237));
        mainScroll.setVerticalScrollBarEnabled(true);
        mainScroll.setScrollbarFadingEnabled(false);
        scrollUp.setOnClickListener(v -> mainScroll.scrollByUser(-dp(620)));
        scrollDown.setOnClickListener(v -> mainScroll.scrollByUser(dp(620)));
        scrollBottom.setOnClickListener(v -> mainScroll.scrollToBottom());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(72));
        mainScroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        window.addView(mainScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(window);

        LinearLayout statusCard = cardContainer();
        addCardTitle(statusCard, "运行状态", "前台服务直接持有蓝牙串口");
        addressView = valueText("监听地址：尚未启动", 15, Color.rgb(25, 25, 25));
        addressView.setTypeface(Typeface.DEFAULT_BOLD);
        statusCard.addView(addressView, matchWrap());
        statusView = valueText("网关未启动。", 13, Color.rgb(53, 53, 53));
        statusView.setLineSpacing(dp(3), 1.0f);
        statusView.setMinHeight(dp(190));
        statusView.setTextIsSelectable(true);
        statusView.setPadding(0, dp(6), 0, 0);
        statusCard.addView(statusView, matchWrap());
        root.addView(statusCard, cardParams());

        LinearLayout radioCard = cardContainer();
        addCardTitle(radioCard, "蓝牙电台", "选择已由系统配对过的 N7500");
        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, devices);
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);
        radioCard.addView(deviceSpinner, matchWrap());
        Button scanButton = makeButton("刷新已配对设备");
        scanButton.setOnClickListener(v -> {
            ensureBluetoothReady();
            refreshDevices();
        });
        LinearLayout.LayoutParams scanParams = matchWrap();
        scanParams.topMargin = dp(8);
        radioCard.addView(scanButton, scanParams);
        root.addView(radioCard, cardParams());

        LinearLayout accessCard = cardContainer();
        addCardTitle(accessCard, "访问模式", "默认只监听本机，不开放公网端口");
        modeSpinner = new Spinner(this);
        ArrayAdapter<String> modes = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[] {"仅本机回环 127.0.0.1", "家庭 Wi-Fi（手动开启）",
                        "Tailscale（手动开启）"});
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modes);
        accessCard.addView(modeSpinner, matchWrap());
        TextView warning = valueText("选择 Wi-Fi 或 Tailscale 后，页面会绑定到一个明确的非回环 IPv4 地址，"
                + "不会绑定 0.0.0.0，也不会做公网端口映射。", 12,
                Color.rgb(150, 100, 28));
        warning.setLineSpacing(dp(2), 1.0f);
        warning.setPadding(0, dp(10), 0, 0);
        accessCard.addView(warning, matchWrap());
        root.addView(accessCard, cardParams());

        LinearLayout optionsCard = cardContainer();
        addCardTitle(optionsCard, "运行选项", "开机恢复、前台服务与屏幕常亮");
        bootSwitch = new Switch(this);
        bootSwitch.setText("开机后自动启动网关");
        bootSwitch.setTextColor(Color.rgb(35, 35, 35));
        optionsCard.addView(bootSwitch, matchWrap());

        foregroundSwitch = new Switch(this);
        foregroundSwitch.setText("强制前台常驻服务（固定启用）");
        foregroundSwitch.setTextColor(Color.rgb(35, 35, 35));
        foregroundSwitch.setChecked(true);
        foregroundSwitch.setEnabled(false);
        optionsCard.addView(foregroundSwitch, matchWrap());

        screenSwitch = new Switch(this);
        screenSwitch.setText("保持屏幕常亮（N1 长时间运行建议开启）");
        screenSwitch.setTextColor(Color.rgb(35, 35, 35));
        optionsCard.addView(screenSwitch, matchWrap());
        root.addView(optionsCard, cardParams());

        LinearLayout actionCard = cardContainer();
        addCardTitle(actionCard, "网关控制", "启动服务后可访问本地只读页面");
        LinearLayout actionRow = horizontalRow();
        startButton = makePrimaryButton("启动网关");
        stopButton = makeButton("停止网关");
        startButton.setOnClickListener(v -> startGateway());
        stopButton.setOnClickListener(v -> stopGateway());
        LinearLayout.LayoutParams firstButton = weighted();
        firstButton.rightMargin = dp(5);
        LinearLayout.LayoutParams secondButton = weighted();
        secondButton.leftMargin = dp(5);
        actionRow.addView(startButton, firstButton);
        actionRow.addView(stopButton, secondButton);
        actionCard.addView(actionRow);

        LinearLayout tokenRow = horizontalRow();
        rotateButton = makeButton("旋转访问令牌");
        copyTokenButton = makeButton("复制当前令牌");
        rotateButton.setOnClickListener(v -> confirmRotateToken());
        copyTokenButton.setOnClickListener(v -> copyToken());
        LinearLayout.LayoutParams rotateParams = weighted();
        rotateParams.rightMargin = dp(5);
        LinearLayout.LayoutParams copyParams = weighted();
        copyParams.leftMargin = dp(5);
        tokenRow.addView(rotateButton, rotateParams);
        tokenRow.addView(copyTokenButton, copyParams);
        actionCard.addView(tokenRow);

        openPageButton = makePrimaryButton("打开本机登录页");
        openPageButton.setOnClickListener(v -> openLoginPage());
        LinearLayout.LayoutParams openParams = matchWrap();
        openParams.topMargin = dp(6);
        actionCard.addView(openPageButton, openParams);
        root.addView(actionCard, cardParams());

        LinearLayout tokenCard = cardContainer();
        tokenCard.setBackground(rounded(Color.rgb(255, 249, 234), 10, Color.rgb(239, 221, 184)));
        addCardTitle(tokenCard, "访问令牌", "一次显示，服务端只保存哈希");
        tokenView = valueText("尚未生成。首次启动网关后会在这里显示一次；若未保存，"
                + "可点击“旋转访问令牌”重新生成。", 13, Color.rgb(126, 89, 19));
        tokenView.setTypeface(Typeface.MONOSPACE);
        tokenView.setTextIsSelectable(true);
        tokenView.setPadding(0, dp(5), 0, 0);
        tokenCard.addView(tokenView, matchWrap());
        root.addView(tokenCard, cardParams());

        TextView footer = valueText("作者 BI4BYF · 安全版本 0.3.8 G1 只读\n"
                + "不提供音量、扫描、信道、发射、音频或固件操作。", 11,
                Color.rgb(128, 128, 128));
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setLineSpacing(dp(2), 1.0f);
        root.addView(footer, matchWrap());
    }

    private void loadSavedSelection() {
        String mode = preferences.getString(GatewayContract.PREF_BIND_MODE,
                GatewayContract.MODE_LOOPBACK);
        if (GatewayContract.MODE_WIFI.equals(mode)) modeSpinner.setSelection(1);
        else if (GatewayContract.MODE_TAILSCALE.equals(mode)) modeSpinner.setSelection(2);
        else modeSpinner.setSelection(0);
        bootSwitch.setChecked(preferences.getBoolean(GatewayContract.PREF_BOOT_ENABLED, false));
        screenSwitch.setChecked(GatewayConfig.screenAlwaysOn(preferences));
    }

    /**
     * Listener must be bound only after setChecked() has restored the persisted values.
     * Binding earlier caused initialization to write the opposite switch state.
     */
    private void bindRuntimeOptionListeners() {
        bootSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (runtimeOptionsReady) persistRuntimeOptions();
        });
        screenSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!runtimeOptionsReady) return;
            persistRuntimeOptions();
            if (!checked) toast("N1 熄屏后可能断开 Wi-Fi；无人值守时建议保持开启");
            if (gateway != null && gateway.isGatewayRunning()) gateway.applyRuntimeOptions();
        });
        runtimeOptionsReady = true;
    }

    private void persistRuntimeOptions() {
        if (!runtimeOptionsReady || preferences == null
                || bootSwitch == null || screenSwitch == null) return;
        preferences.edit()
                .putBoolean(GatewayContract.PREF_BOOT_ENABLED, bootSwitch.isChecked())
                .putBoolean(GatewayContract.PREF_SCREEN_ALWAYS_ON, screenSwitch.isChecked())
                .apply();
        if (bootSwitch.isChecked()) GatewayBootScheduler.schedule(this);
        else GatewayBootScheduler.cancel(this);
    }

    private void refreshDevices() {
        devices.clear();
        String savedAddress = preferences.getString(GatewayContract.PREF_DEVICE_ADDRESS, null);
        int selectedIndex = 0;
        if (adapter == null) {
            devices.add(new DeviceItem(null, "本机不支持蓝牙"));
        } else if (!hasLocationPermission()) {
            devices.add(new DeviceItem(null, "等待定位权限后读取已配对设备"));
        } else if (!adapter.isEnabled()) {
            devices.add(new DeviceItem(null, "蓝牙未开启"));
        } else {
            try {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                for (BluetoothDevice device : bonded) {
                    String name = safeDeviceName(device);
                    DeviceItem item = new DeviceItem(device.getAddress(), name);
                    if (savedAddress != null && savedAddress.equals(device.getAddress())) {
                        selectedIndex = devices.size();
                    }
                    devices.add(item);
                }
            } catch (SecurityException e) {
                devices.add(new DeviceItem(null, "无权读取已配对设备"));
            }
            if (devices.isEmpty()) devices.add(new DeviceItem(null, "没有已配对设备，请先在系统蓝牙中配对 N7500"));
        }
        deviceAdapter.notifyDataSetChanged();
        if (!devices.isEmpty()) deviceSpinner.setSelection(Math.min(selectedIndex, devices.size() - 1));
    }

    private void startGateway() {
        ensureBluetoothReady();
        if (!hasLocationPermission()) {
            requestRequiredPermissions();
            toast("请先授予定位权限，再启动蓝牙网关");
            return;
        }
        DeviceItem item = selectedDevice();
        if (item == null || item.address == null) {
            toast("请选择一个已经配对且处于开启状态的 N7500");
            return;
        }
        String mode = selectedMode();
        GatewayConfig.saveSelection(preferences, item.address, item.label, mode,
                bootSwitch.isChecked(), screenSwitch.isChecked());
        if (bootSwitch.isChecked()) GatewayBootScheduler.schedule(this);
        Intent intent = new Intent(this, GatewayService.class)
                .setAction(GatewayService.ACTION_START);
        startService(intent);
        if (!bound) {
            bindService(new Intent(this, GatewayService.class), connection, BIND_AUTO_CREATE);
        }
        toast("正在启动网关");
        ui.postDelayed(this::renderStatus, 250L);
    }

    private void stopGateway() {
        Intent intent = new Intent(this, GatewayService.class)
                .setAction(GatewayService.ACTION_STOP);
        startService(intent);
        displayedToken = null;
        showToken(null);
        ui.postDelayed(this::renderStatus, 250L);
    }

    private void confirmRotateToken() {
        if (gateway == null || !gateway.isGatewayRunning()) {
            toast("网关尚未启动");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("旋转访问令牌")
                .setMessage("旧令牌和所有浏览器登录会话都会立即失效。继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("旋转", (dialog, which) -> {
                    String value = gateway.rotateToken();
                    if (value != null) {
                        showToken(value);
                        toast("新令牌已生成，只显示在当前页面");
                    } else {
                        toast("令牌生成失败");
                    }
                })
                .show();
    }

    private void copyToken() {
        if (displayedToken == null || displayedToken.isEmpty()) {
            toast("当前没有可复制的令牌，请先启动或旋转令牌");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("N1Gateway access token", displayedToken));
        toast("访问令牌已复制");
    }

    private void openLoginPage() {
        if (gateway == null || !gateway.isGatewayRunning()) {
            toast("网关尚未启动");
            return;
        }
        String address = gateway.gatewayBindAddress();
        if (address == null || address.isEmpty()) address = "127.0.0.1";
        String url = "http://" + address + ":" + gateway.gatewayPort() + "/login";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("本机没有可打开网页的浏览器：" + url);
        }
    }

    private void renderStatus() {
        if (mainScroll != null) mainScroll.preservePosition();
        renderStatusContent();
    }

    private void renderStatusContent() {
        if (gateway == null) {
            setTextIfChanged(addressView, "监听地址：服务尚未绑定");
            setTextIfChanged(statusView, "网关未启动。点击“启动网关”后会创建前台服务和本地网页。");
            setHeaderStatus("网关未启动", Color.rgb(158, 158, 158));
            setRunningButtons(false);
            return;
        }
        boolean running = gateway.isGatewayRunning();
        setRunningButtons(running);
        setTextIfChanged(addressView, running
                ? "监听地址：" + gateway.gatewayBindAddress() + ":" + gateway.gatewayPort()
                : "监听地址：尚未启动");
        if (!running) {
            setTextIfChanged(statusView, "网关未启动。");
            setHeaderStatus("网关未启动", Color.rgb(158, 158, 158));
            return;
        }
        setHeaderStatus("正在读取状态", Color.rgb(255, 179, 0));
        String newToken = gateway.consumePendingToken();
        if (newToken != null) showToken(newToken);
        try {
            JSONObject json = new JSONObject(gateway.snapshotJson());
            String gatewayState = stringValue(json, "gateway", "state");
            String connectionState = stringValue(json, "connection", "state");
            String device = stringValue(json, "connection", "deviceLabel");
            String gatewayError = stringValue(json, "gateway", "lastErrorCode");
            String connectionError = stringValue(json, "connection", "lastError");
            String errorText = "";
            if ("RADIO_READ_TIMEOUT".equals(gatewayError)) {
                errorText = "\n提示：蓝牙已连接但连续读取超时。请关闭 HT 和 N1Control 的蓝牙连接，然后停止并重新启动网关。";
            } else if (gatewayError != null) {
                errorText = "\n网关错误：" + gatewayError;
            }
            if (connectionError != null) {
                errorText += "\n连接错误：" + connectionError;
            }
            boolean stale = json.optBoolean("stale", true);
            boolean online = "ONLINE".equals(gatewayState);
            boolean connected = "CONNECTED".equals(connectionState);
            if (online && connected && !stale) {
                setHeaderStatus("电台已连接", Color.rgb(7, 193, 96));
            } else if (connected) {
                setHeaderStatus("快照已过期", Color.rgb(255, 179, 0));
            } else {
                setHeaderStatus("电台未连接", Color.rgb(229, 57, 53));
            }
            String updatedAt = json.isNull("updatedAt") ? "尚未读取" : json.optString("updatedAt");
            String product = stringValue(json, "radio", "productId");
            String software = stringValue(json, "radio", "softwareVersion");
            String channelName = "未读取";
            JSONObject channel = json.optJSONObject("channel");
            if (channel != null) {
                channelName = channel.optString("name", "未命名") + " / "
                        + channel.optString("rxMHz", "?") + " MHz";
            }
            JSONObject radio = json.optJSONObject("radio");
            JSONObject status = radio == null ? null : radio.optJSONObject("status");
            String receive = status == null ? "未知" : booleanText(status, "receiving");
            String transmit = status == null ? "未知" : booleanText(status, "transmitting");
            int volume = json.isNull("volume") ? -1 : json.optInt("volume", -1);
            String diagnostics;
            String runtimeLocks;
            try {
                diagnostics = gateway.radioDiagnostics();
                runtimeLocks = gateway.runtimeLocksStatus();
            } catch (RuntimeException e) {
                diagnostics = "统计不可用";
                runtimeLocks = "状态不可用";
            }
            setTextIfChanged(statusView, "网关：" + gatewayState
                    + "\n连接：" + connectionState + "　设备：" + device
                    + "\n产品：" + product + "　软件：" + software
                    + "\n信道：" + channelName
                    + "\n接收：" + receive + "　发射：" + transmit
                    + "　音量：" + (volume < 0 ? "未读取" : volume)
                    + "\n快照：" + (stale ? "已过期 / 最后已知值" : "实时")
                    + "　更新时间：" + updatedAt
                    + "\n保活：" + runtimeLocks
                    + "\n链路：" + diagnostics
                    + errorText);
        } catch (Exception e) {
            setTextIfChanged(statusView, "快照解析失败，请查看系统日志等待重连。");
            setHeaderStatus("状态读取失败", Color.rgb(229, 57, 53));
        }
    }

    private void setRunningButtons(boolean running) {
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        rotateButton.setEnabled(running);
        openPageButton.setEnabled(running);
    }

    private void showToken(String token) {
        displayedToken = token;
        if (token == null || token.isEmpty()) {
            setTextIfChanged(tokenView, "当前页面没有保留令牌。令牌原文不会写入配置或日志；"
                    + "如需重新获取，请点击“旋转访问令牌”。");
        } else {
            setTextIfChanged(tokenView, "10位访问令牌（请立即保存）：\n" + token
                    + "\n\n关闭应用后原文不会再显示；服务会继续只保存哈希。");
        }
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (view == null || text == null) return;
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private void requestRequiredPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && !hasLocationPermission()) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_PERMISSIONS);
        }
    }

    private boolean hasLocationPermission() {
        return android.os.Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureBluetoothReady() {
        if (adapter == null) {
            toast("本机没有蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    REQUEST_ENABLE_BT);
        }
    }

    private DeviceItem selectedDevice() {
        Object selected = deviceSpinner == null ? null : deviceSpinner.getSelectedItem();
        return selected instanceof DeviceItem ? (DeviceItem) selected : null;
    }

    private String selectedMode() {
        switch (modeSpinner.getSelectedItemPosition()) {
            case 1: return GatewayContract.MODE_WIFI;
            case 2: return GatewayContract.MODE_TAILSCALE;
            default: return GatewayContract.MODE_LOOPBACK;
        }
    }

    private static String safeDeviceName(BluetoothDevice device) {
        try {
            String value = device.getName();
            return value == null || value.trim().isEmpty() ? "N7500" : value.trim();
        } catch (SecurityException e) {
            return "N7500";
        }
    }

    private static String maskAddress(String address) {
        if (address == null) return "";
        String[] parts = address.split(":");
        if (parts.length < 2) return "**";
        return "**:**:**:**:**:" + parts[parts.length - 1];
    }

    private static String stringValue(JSONObject root, String objectName, String field) {
        JSONObject object = root.optJSONObject(objectName);
        if (object == null || object.isNull(field)) return "无";
        return object.optString(field, "无");
    }

    private static String booleanText(JSONObject root, String field) {
        if (root.isNull(field)) return "未知";
        return root.optBoolean(field, false) ? "是" : "否";
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(Color.WHITE, 10, 0));
        card.setElevation(dp(1));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        return params;
    }

    private void addCardTitle(LinearLayout card, String title, String subtitle) {
        TextView heading = valueText(title, 15, Color.rgb(25, 25, 25));
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(heading, matchWrap());
        TextView note = valueText(subtitle, 11, Color.rgb(133, 133, 133));
        note.setPadding(0, dp(2), 0, dp(9));
        card.addView(note, matchWrap());
    }

    private GradientDrawable rounded(int fillColor, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void setHeaderStatus(String text, int dotColor) {
        if (headerStatusView != null) headerStatusView.setText(text);
        if (headerDotView != null) headerDotView.setTextColor(dotColor);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinHeight(dp(44));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setTextColor(Color.rgb(44, 44, 44));
        button.setBackground(rounded(Color.rgb(237, 237, 237), 7, 0));
        button.setElevation(0);
        return button;
    }

    private Button makePrimaryButton(String text) {
        Button button = makeButton(text);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(Color.rgb(7, 193, 96), 7, 0));
        return button;
    }

    private Button makeToolbarButton(String text) {
        Button button = makeButton(text);
        button.setTextSize(12);
        button.setTextColor(Color.WHITE);
        button.setBackground(rounded(0x33FFFFFF, 5, 0x66FFFFFF));
        button.setMinWidth(dp(54));
        button.setMinHeight(dp(34));
        button.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(54), dp(34));
        params.leftMargin = dp(4);
        button.setLayoutParams(params);
        return button;
    }

    private TextView valueText(String text, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setPadding(0, 0, 0, 0);
        return view;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class DeviceItem {
        final String address;
        final String label;
        DeviceItem(String address, String label) {
            this.address = address;
            this.label = label;
        }
        @Override public String toString() {
            return address == null ? label : label + "　[" + maskAddress(address) + "]";
        }
    }

    /**
     * Status text changes once per second. The ScrollView keeps an anchor only
     * after an explicit user scroll, then restores that anchor after background
     * layouts and focus requests. Background refreshes can never overwrite it
     * with the current top position.
     */
    private static final class PositionStableScrollView extends ScrollView {
        private static final long LAYOUT_PRESERVE_MS = 700L;
        private static final int ANCHOR_NONE = -1;

        private int anchorY = ANCHOR_NONE;
        private boolean anchorAtBottom;
        private boolean touching;
        private boolean followUserScroll;
        private boolean restoringAnchor;

        private final Runnable settleUserScroll = new Runnable() {
            @Override public void run() {
                if (touching) return;
                followUserScroll = false;
                anchorAtBottom = false;
                anchorY = getScrollY();
            }
        };

        PositionStableScrollView(Context context) {
            super(context);
            setFocusable(true);
            setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        }

        void preservePosition() {
            if (touching || followUserScroll || anchorY == ANCHOR_NONE) return;
            restoreAnchor();
        }

        void scrollByUser(int deltaY) {
            removeCallbacks(settleUserScroll);
            touching = false;
            followUserScroll = false;
            anchorAtBottom = false;
            anchorY = getScrollY();
            scrollBy(0, deltaY);
            anchorY = getScrollY();
        }

        void scrollToBottom() {
            removeCallbacks(settleUserScroll);
            touching = false;
            followUserScroll = false;
            anchorAtBottom = true;
            anchorY = maxScrollY();
            scrollTo(0, anchorY);
        }

        void releaseAnchorForFocusNavigation() {
            removeCallbacks(settleUserScroll);
            touching = false;
            followUserScroll = false;
            restoringAnchor = false;
            anchorAtBottom = false;
            anchorY = ANCHOR_NONE;
        }

        @Override protected void onLayout(boolean changed, int left, int top,
                                           int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (anchorY != ANCHOR_NONE) restoreAnchor();
        }

        @Override public void requestChildFocus(View child, View focused) {
            if (anchorY == ANCHOR_NONE) {
                super.requestChildFocus(child, focused);
                return;
            }
            boolean previous = restoringAnchor;
            restoringAnchor = true;
            try {
                super.requestChildFocus(child, focused);
            } finally {
                restoringAnchor = previous;
            }
            restoreAnchor();
        }

        @Override protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
            super.onScrollChanged(left, top, oldLeft, oldTop);
            if (followUserScroll && !restoringAnchor) {
                anchorAtBottom = false;
                anchorY = top;
            }
        }

        @Override public void computeScroll() {
            super.computeScroll();
            if (followUserScroll && !restoringAnchor) {
                anchorAtBottom = false;
                anchorY = getScrollY();
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                removeCallbacks(settleUserScroll);
                touching = true;
                followUserScroll = true;
                anchorAtBottom = false;
                anchorY = getScrollY();
            }
            boolean handled = super.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touching = false;
                followUserScroll = true;
                removeCallbacks(settleUserScroll);
                postDelayed(settleUserScroll, LAYOUT_PRESERVE_MS);
            }
            return handled;
        }

        private void restoreAnchor() {
            if (anchorY == ANCHOR_NONE) return;
            int target = anchorAtBottom ? maxScrollY() : clampToContent(anchorY);
            if (getScrollY() != target) {
                boolean previous = restoringAnchor;
                restoringAnchor = true;
                try {
                    scrollTo(0, target);
                } finally {
                    restoringAnchor = previous;
                }
            }
            if (!anchorAtBottom) anchorY = getScrollY();
        }

        private int maxScrollY() {
            View child = getChildAt(0);
            if (child == null) return 0;
            return Math.max(0, child.getHeight() - getHeight());
        }

        private int clampToContent(int scrollY) {
            return Math.max(0, Math.min(scrollY, maxScrollY()));
        }
    }
}
