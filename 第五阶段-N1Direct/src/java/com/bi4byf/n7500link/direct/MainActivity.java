package com.bi4byf.n7500link.direct;

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
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.text.InputType;
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
    private FrameLayout pageHost;
    private final ArrayList<View> pages = new ArrayList<>();
    private final ArrayList<Button> menuButtons = new ArrayList<>();
    private int currentPage = -1;
    private Button startButton;
    private Button stopButton;
    private Button rotateButton;
    private Button copyTokenButton;
    private Button openPageButton;
    private Button setCustomTokenButton;
    private EditText customTokenInput;
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
            if (!gateway.isGatewayRunning()) {
                // BIND_AUTO_CREATE alone creates the Service instance but does not
                // run onStartCommand, so opening the app after an update used to
                // leave the HTTP listener and radio session stopped.
                gateway.startGateway();
            }
            String newToken = gateway.consumePendingToken();
            if (newToken != null) { showToken(newToken); }
            else if (gateway.currentToken() != null) { showToken(gateway.currentToken()); }
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
        headerStatusView.setText("直连控制 · 正在初始化");
        headerStatusView.setTextSize(11);
        headerStatusView.setTextColor(Color.rgb(224, 247, 235));
        headerStatusView.setPadding(dp(2), 0, 0, 0);
        statusRow.addView(headerStatusView);
        titleColumn.addView(statusRow);

        TextView readonly = new TextView(this);
        readonly.setText("直连控制");
        readonly.setTextSize(11);
        readonly.setTextColor(Color.WHITE);
        readonly.setGravity(Gravity.CENTER);
        readonly.setPadding(dp(9), dp(5), dp(9), dp(5));
        readonly.setBackground(rounded(0x22FFFFFF, 5, 0x66FFFFFF));
        header.addView(readonly);
        window.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(12), dp(12), dp(12), dp(8));

        LinearLayout menuBar = new LinearLayout(this);
        menuBar.setOrientation(LinearLayout.VERTICAL);
        menuBar.setPadding(dp(8), dp(10), dp(8), dp(8));
        menuBar.setBackground(rounded(Color.rgb(250, 250, 250), 12,
                Color.rgb(222, 224, 226)));
        body.addView(menuBar, new LinearLayout.LayoutParams(dp(184),
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView menuTitle = valueText("功能菜单", 11, Color.rgb(145, 145, 145));
        menuTitle.setTypeface(Typeface.DEFAULT_BOLD);
        menuTitle.setPadding(dp(12), 0, 0, dp(8));
        menuBar.addView(menuTitle, matchWrap());

        String[] menuLabels = {
                "运行状态", "蓝牙电台", "网络设置", "运行选项", "网关与令牌"
        };
        for (int i = 0; i < menuLabels.length; i++) {
            final int pageIndex = i;
            Button menuButton = makeMenuButton(menuLabels[i]);
            menuButton.setOnClickListener(v -> selectPage(pageIndex));
            LinearLayout.LayoutParams menuParams = matchWrap();
            menuParams.height = dp(50);
            menuParams.bottomMargin = dp(6);
            menuBar.addView(menuButton, menuParams);
            menuButtons.add(menuButton);
        }

        LinearLayout rightColumn = new LinearLayout(this);
        rightColumn.setOrientation(LinearLayout.VERTICAL);
        rightColumn.setPadding(dp(10), 0, 0, 0);
        body.addView(rightColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        pageHost = new FrameLayout(this);
        rightColumn.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = valueText("作者 BI4BYF · 直连控制版本 0.8.0 · "
                + "不提供 PTT、音频或频率/亚音/信道记录写入", 10, Color.rgb(128, 128, 128));
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, dp(5), 0, dp(2));
        rightColumn.addView(footer, matchWrap());

        window.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(window);

        LinearLayout statusPage = pageContainer();
        LinearLayout statusCard = cardContainer();
        addCardTitle(statusCard, "运行状态", "前台服务直接持有蓝牙串口");
        addressView = valueText("监听地址：尚未启动", 15, Color.rgb(25, 25, 25));
        addressView.setTypeface(Typeface.DEFAULT_BOLD);
        statusCard.addView(addressView, matchWrap());
        statusView = valueText("网关未启动。", 13, Color.rgb(53, 53, 53));
        statusView.setLineSpacing(dp(3), 1.0f);
        statusView.setTextIsSelectable(true);
        statusView.setPadding(0, dp(6), 0, 0);
        statusCard.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        statusPage.addView(statusCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout radioPage = pageContainer();
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
        radioPage.addView(radioCard, cardParams());

        LinearLayout accessPage = pageContainer();
        LinearLayout accessCard = cardContainer();
        addCardTitle(accessCard, "访问模式", "默认家庭 Wi-Fi；也可限制为仅本机");
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
        accessPage.addView(accessCard, cardParams());

        LinearLayout optionsPage = pageContainer();
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
        optionsPage.addView(optionsCard, cardParams());

        LinearLayout gatewayPage = pageContainer();
        LinearLayout actionCard = cardContainer();
        addCardTitle(actionCard, "网关控制", "启动服务后可访问本地直连控制页面");
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
        rotateButton.setOnClickListener(v -> confirmRotateToken(false));
        copyTokenButton.setOnClickListener(v -> copyToken());
        LinearLayout.LayoutParams rotateParams = weighted();
        rotateParams.rightMargin = dp(5);
        LinearLayout.LayoutParams copyParams = weighted();
        copyParams.leftMargin = dp(5);
        tokenRow.addView(rotateButton, rotateParams);
        tokenRow.addView(copyTokenButton, copyParams);
        actionCard.addView(tokenRow);

        TextView customTokenLabel = valueText("主动设置访问令牌（10–64 位字母或数字）", 12,
                Color.rgb(110, 110, 110));
        customTokenLabel.setPadding(0, dp(12), 0, dp(4));
        actionCard.addView(customTokenLabel, matchWrap());

        LinearLayout customTokenRow = horizontalRow();
        customTokenInput = new EditText(this);
        customTokenInput.setSingleLine(true);
        customTokenInput.setHint("输入新令牌");
        customTokenInput.setTextSize(13);
        customTokenInput.setTextColor(Color.rgb(35, 35, 35));
        customTokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setCustomTokenButton = makeButton("设置");
        setCustomTokenButton.setOnClickListener(v -> confirmSetCustomToken());
        LinearLayout.LayoutParams customInputParams = weighted();
        customInputParams.rightMargin = dp(8);
        customTokenRow.addView(customTokenInput, customInputParams);
        LinearLayout.LayoutParams customButtonParams =
                new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT);
        customTokenRow.addView(setCustomTokenButton, customButtonParams);
        actionCard.addView(customTokenRow);

        openPageButton = makePrimaryButton("复制令牌并打开登录页");
        openPageButton.setOnClickListener(v -> openLoginPage());
        LinearLayout.LayoutParams openParams = matchWrap();
        openParams.topMargin = dp(6);
        actionCard.addView(openPageButton, openParams);
        gatewayPage.addView(actionCard, cardParams());

        LinearLayout tokenCard = cardContainer();
        tokenCard.setBackground(rounded(Color.rgb(255, 249, 234), 10, Color.rgb(239, 221, 184)));
        addCardTitle(tokenCard, "访问令牌", "令牌保存在 N1 私有存储，可随时回到此页复制");
        tokenView = valueText("令牌会保存在 N1 应用私有存储，可随时复制。"
                + "若此页仍显示未建立，请点击“旋转访问令牌”生成新令牌。", 13, Color.rgb(126, 89, 19));
        tokenView.setTypeface(Typeface.MONOSPACE);
        tokenView.setTextIsSelectable(true);
        tokenView.setPadding(0, dp(5), 0, 0);
        tokenCard.addView(tokenView, matchWrap());
        gatewayPage.addView(tokenCard, cardParams());

        selectPage(0);
    }

    private LinearLayout pageContainer() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setVisibility(View.GONE);
        pageHost.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages.add(page);
        return page;
    }

    private void selectPage(int index) {
        if (index < 0 || index >= pages.size()) return;
        currentPage = index;
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < menuButtons.size(); i++) {
            menuButtons.get(i).setSelected(i == index);
        }
    }


    private void loadSavedSelection() {
        String mode = preferences.getString(GatewayContract.PREF_BIND_MODE,
                GatewayContract.MODE_WIFI);
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
        String retained = gateway == null ? null : gateway.currentToken();
        displayedToken = retained;
        showToken(retained);
        ui.postDelayed(this::renderStatus, 250L);
    }

    private void confirmRotateToken(boolean copyAfter) {
        confirmRotateToken(copyAfter, false);
    }

    private void confirmRotateToken(boolean copyAfter, boolean openAfter) {
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
                        if (copyAfter) {
                            copyCurrentTokenToClipboard();
                        } else {
                            toast("新令牌已保存在 N1 应用，可随时复制");
                        }
                        if (openAfter) launchLoginPage();
                    } else {
                        toast("令牌生成失败");
                    }
                })
                .show();
    }

    private void confirmSetCustomToken() {
        if (gateway == null || !gateway.isGatewayRunning()) {
            toast("网关尚未启动");
            return;
        }
        if (customTokenInput == null) return;
        String normalized = AuthManager.normalizeToken(customTokenInput.getText().toString());
        if (!AuthManager.isValidAccessToken(normalized)) {
            toast("令牌须为 10–64 位 ASCII 字母或数字");
            customTokenInput.requestFocus();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("主动设置访问令牌")
                .setMessage("保存后，旧令牌和所有浏览器登录会话都会立即失效。继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    if (gateway.setCustomAccessToken(normalized)) {
                        customTokenInput.setText("");
                        showToken(normalized);
                        toast("访问令牌已更新，旧浏览器会话已失效");
                    } else {
                        toast("令牌设置失败，请确认网关正在运行");
                    }
                })
                .show();
    }

    private boolean ensureCurrentToken() {
        if (displayedToken != null && !displayedToken.isEmpty()) return true;
        if (gateway == null) return false;
        String retained = gateway.currentToken();
        if (retained == null || retained.isEmpty()) return false;
        showToken(retained);
        return true;
    }

    private void copyToken() {
        if (!ensureCurrentToken()) {
            showToken(null);
            confirmRotateToken(true);
            return;
        }
        copyCurrentTokenToClipboard();
    }

    private void copyCurrentTokenToClipboard() {
        if (displayedToken == null || displayedToken.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "N1Gateway access token", displayedToken));
        toast("访问令牌已复制，可粘贴到登录页");
    }

    private void openLoginPage() {
        if (gateway == null || !gateway.isGatewayRunning()) {
            toast("网关尚未启动");
            return;
        }
        if (!ensureCurrentToken()) {
            showToken(null);
            confirmRotateToken(true, true);
            return;
        }
        copyCurrentTokenToClipboard();
        launchLoginPage();
    }

    private void launchLoginPage() {
        if (gateway == null || !gateway.isGatewayRunning()) return;
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
        if (newToken != null) { showToken(newToken); }
        else { ensureCurrentToken(); }
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
        if (setCustomTokenButton != null) setCustomTokenButton.setEnabled(running);
        if (customTokenInput != null) customTokenInput.setEnabled(running);
        openPageButton.setEnabled(running);
    }

    private void showToken(String token) {
        displayedToken = token;
        if (token == null || token.isEmpty()) {
            setTextIfChanged(tokenView, "当前尚未建立可恢复令牌。请点击“旋转访问令牌”，"
                    + "新令牌会保存在 N1 应用私有存储。");
        } else {
            setTextIfChanged(tokenView, "访问令牌（当前登录用）：\n" + token
                    + "\n\n令牌保存在 N1 应用私有存储，可随时回到此页复制；"
                    + "旋转或主动修改后，旧登录会话会失效。");
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

    private Button makeMenuButton(String text) {
        Button button = makeButton(text);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setTextSize(14);
        button.setMinHeight(dp(50));
        button.setPadding(dp(14), 0, dp(10), 0);
        button.setFocusable(true);
        button.setClickable(true);

        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_selected},
                rounded(Color.rgb(7, 193, 96), 7, 0));
        background.addState(new int[] {android.R.attr.state_focused},
                rounded(Color.rgb(224, 247, 235), 7, Color.rgb(7, 193, 96)));
        background.addState(new int[] {android.R.attr.state_pressed},
                rounded(Color.rgb(205, 242, 222), 7, Color.rgb(7, 193, 96)));
        background.addState(new int[] {}, rounded(Color.TRANSPARENT, 7, 0));
        button.setBackground(background);
        button.setTextColor(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_selected},
                        new int[] {android.R.attr.state_focused}, new int[] {}},
                new int[] {Color.WHITE, Color.rgb(7, 150, 80), Color.rgb(44, 44, 44)}));
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

}


