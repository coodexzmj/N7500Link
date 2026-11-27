package com.bi4byf.n7500link.control;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements BluetoothLink.Listener, GaiaCodec.Listener {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_ENABLE_BT = 11;
    private static final int LOG_LIMIT = 500000;
    private static final int STABILITY_WRITES = 50;
    private static final long WRITE_SETTLE_MS = 900;
    private static final long READ_TIMEOUT_MS = 3000;
    private static final String LOG_DIR_NAME = "N1Control-logs";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final GaiaCodec codec = new GaiaCodec();

    private final StringBuilder logBuffer = new StringBuilder();
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final ArrayList<PendingRequest> pendingRequests = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private final SimpleDateFormat fileFormat = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    private final RadioState.WriteGuard writeGuard = new RadioState.WriteGuard();
    private final RadioState.InFlightRequestGuard requestGuard =
            new RadioState.InFlightRequestGuard();

    private BluetoothAdapter adapter;
    private BluetoothLink link;
    private ArrayAdapter<String> deviceAdapter;
    private Spinner deviceSpinner;
    private TextView connectionStatus;
    private TextView deviceInfoView;
    private TextView settingsView;
    private TextView statusView;
    private TextView volumeView;
    private TextView channelView;
    private TextView undoView;
    private TextView operationStatusView;
    private TextView stabilityView;
    private TextView statsView;
    private TextView logView;
    private TextView logPathView;
    private SeekBar volumeSeek;
    private TextView volumeValue;
    private EditText channelEdit;
    private Button connectButton;
    private Button disconnectButton;
    private Button refreshButton;
    private Button writeVolumeButton;
    private Button restoreVolumeButton;
    private Button toggleScanButton;
    private Button restoreSettingsButton;
    private Button readChannelButton;
    private Button selectAButton;
    private Button selectBButton;
    private Button stabilityButton;

    private File logDirectory;
    private File sessionLogFile;
    private String environmentReport;
    private long txFrames;
    private long txBytes;
    private int requestSequence;
    private boolean autoReadStarted;
    private RadioState.SettingsSnapshot lastSettings;
    private RadioState.VolumeSnapshot lastVolume;
    private SettingsUndo lastSettingsUndo;
    private int lastVolumeBeforeWrite = -1;
    private WriteSession activeWrite;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        adapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        prepareLogging();
        refreshPairedDevices();
        updateConnectionUi(false);
        appendLog("[系统] N1Control 启动，版本 0.2.0；仅开放音量和信道扫描等低风险写入，不包含 PTT、音频或网络功能。");
        if (adapter == null) {
            appendLog("[系统] 本机没有可用的蓝牙适配器。");
        } else if (!adapter.isEnabled()) {
            appendLog("[系统] 系统蓝牙尚未开启。");
        }
        requestRuntimePermissions();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244, 246, 248));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("N1Control · N7500 低风险控制");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(24, 36, 48));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("独立蓝牙直连 · 不使用 HT 账号或服务器 · 写入后回读验证 · 失败只回滚一次");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.rgb(82, 96, 110));
        subtitle.setPadding(0, dp(3), 0, dp(12));
        root.addView(subtitle);

        addSection(root, "蓝牙连接");
        deviceSpinner = new Spinner(this);
        deviceSpinner.setMinimumHeight(dp(48));
        deviceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<String>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);
        root.addView(deviceSpinner, matchWrap());

        LinearLayout pairingRow = horizontalRow();
        Button refreshDevicesButton = makeButton("刷新已配对设备", false);
        refreshDevicesButton.setOnClickListener(v -> refreshPairedDevices());
        Button pairingButton = makeButton("打开蓝牙配对", false);
        pairingButton.setOnClickListener(v -> openBluetoothSettings());
        pairingRow.addView(refreshDevicesButton, weighted());
        pairingRow.addView(pairingButton, weighted());
        root.addView(pairingRow);

        LinearLayout connectRow = horizontalRow();
        connectButton = makeButton("连接 N7500", true);
        connectButton.setOnClickListener(v -> connectSelectedDevice());
        disconnectButton = makeButton("断开", false);
        disconnectButton.setOnClickListener(v -> disconnectRadio());
        connectRow.addView(connectButton, weighted());
        connectRow.addView(disconnectButton, weighted());
        root.addView(connectRow);

        connectionStatus = valueText("状态：未连接");
        connectionStatus.setTextColor(Color.rgb(155, 62, 42));
        root.addView(connectionStatus);

        addSection(root, "设备快照");
        deviceInfoView = valueText("设备信息：尚未读取");
        root.addView(deviceInfoView, matchWrap());
        settingsView = valueText("设置：尚未读取");
        root.addView(settingsView, matchWrap());
        statusView = valueText("电台状态：尚未读取");
        root.addView(statusView, matchWrap());
        volumeView = valueText("音量：尚未读取");
        root.addView(volumeView, matchWrap());
        channelView = valueText("信道：尚未读取");
        root.addView(channelView, matchWrap());

        LinearLayout snapshotRow = horizontalRow();
        refreshButton = makeButton("刷新全部快照", false);
        refreshButton.setOnClickListener(v -> refreshSnapshot());
        Button registerButton = makeButton("注册状态通知", false);
        registerButton.setOnClickListener(v -> sendRegisterNotification());
        snapshotRow.addView(refreshButton, weighted());
        snapshotRow.addView(registerButton, weighted());
        root.addView(snapshotRow);

        addSection(root, "音量控制（0 ~ 15）");
        volumeValue = valueText("目标音量：8 / 15");
        volumeValue.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(volumeValue);
        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(15);
        volumeSeek.setProgress(8);
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText("目标音量：" + progress + " / 15");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(volumeSeek, matchWrap());

        LinearLayout volumeRow = horizontalRow();
        writeVolumeButton = makeButton("写入并回读音量", true);
        writeVolumeButton.setOnClickListener(v -> writeSelectedVolume());
        restoreVolumeButton = makeButton("恢复上次音量", false);
        restoreVolumeButton.setOnClickListener(v -> restoreLastVolume());
        volumeRow.addView(writeVolumeButton, weighted());
        volumeRow.addView(restoreVolumeButton, weighted());
        root.addView(volumeRow);

        addSection(root, "扫描与当前信道");
        undoView = valueText("可恢复设置：尚无成功写入记录");
        undoView.setTextSize(12);
        root.addView(undoView, matchWrap());

        LinearLayout settingsRow = horizontalRow();
        toggleScanButton = makeButton("切换扫描状态", true);
        toggleScanButton.setOnClickListener(v -> toggleScan());
        restoreSettingsButton = makeButton("恢复上次设置", false);
        restoreSettingsButton.setOnClickListener(v -> restoreLastSettings());
        settingsRow.addView(toggleScanButton, weighted());
        settingsRow.addView(restoreSettingsButton, weighted());
        root.addView(settingsRow);

        LinearLayout channelRow = horizontalRow();
        TextView channelLabel = new TextView(this);
        channelLabel.setText("信道索引");
        channelLabel.setTextSize(14);
        channelLabel.setTextColor(Color.rgb(50, 62, 74));
        channelLabel.setGravity(Gravity.CENTER_VERTICAL);
        channelRow.addView(channelLabel, wrapWrap());
        channelEdit = new EditText(this);
        channelEdit.setSingleLine(true);
        channelEdit.setText("0");
        channelEdit.setTextSize(16);
        channelEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        channelRow.addView(channelEdit, weighted());
        readChannelButton = makeButton("读取信道记录", false);
        readChannelButton.setOnClickListener(v -> readChannelFromInput());
        channelRow.addView(readChannelButton, weighted());
        root.addView(channelRow);

        LinearLayout selectRow = horizontalRow();
        selectAButton = makeButton("选择信道 A", false);
        selectAButton.setOnClickListener(v -> selectDoubleChannel(1));
        selectBButton = makeButton("选择信道 B", false);
        selectBButton.setOnClickListener(v -> selectDoubleChannel(2));
        selectRow.addView(selectAButton, weighted());
        selectRow.addView(selectBButton, weighted());
        root.addView(selectRow);

        TextView channelHint = valueText("这里只切换已经保存的 A/B 信道选择，不写入频率、亚音、带宽或信道记录。");
        channelHint.setTextSize(12);
        root.addView(channelHint, matchWrap());

        addSection(root, "稳定性自检");
        stabilityView = valueText("同一音量连续写入并回读 50 次；不改变目标音量。");
        stabilityView.setTextSize(12);
        root.addView(stabilityView, matchWrap());
        stabilityButton = makeButton("开始 50 次音量稳定性自检", false);
        stabilityButton.setOnClickListener(v -> startVolumeStabilityTest());
        root.addView(stabilityButton, matchWrap());

        addSection(root, "操作状态");
        operationStatusView = valueText("空闲，未执行写入。");
        operationStatusView.setTextColor(Color.rgb(55, 85, 110));
        root.addView(operationStatusView, matchWrap());

        addSection(root, "原始收发记录");
        statsView = valueText("RX 块 0 / 0 字节 / 0 帧 / 丢弃 0 字节　TX 0 帧 / 0 字节");
        statsView.setTypeface(Typeface.MONOSPACE);
        root.addView(statsView, matchWrap());

        LinearLayout logButtons = horizontalRow();
        Button copyButton = makeButton("复制日志", false);
        copyButton.setOnClickListener(v -> copyLog());
        Button saveButton = makeButton("保存日志", true);
        saveButton.setOnClickListener(v -> saveLogAndReport(false));
        logButtons.addView(copyButton, weighted());
        logButtons.addView(saveButton, weighted());
        root.addView(logButtons);

        logPathView = valueText("日志保存位置：准备中");
        logPathView.setTextSize(12);
        root.addView(logPathView, matchWrap());

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.rgb(225, 232, 238));
        logView.setBackgroundColor(Color.rgb(24, 30, 38));
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setMinHeight(dp(330));
        root.addView(logView, matchWrap());

        TextView footer = valueText("安全边界：不发送 PTT、音频、信道记录写入、频率编辑、固件升级或网络请求；所有设置写入都必须回读确认。");
        footer.setTextColor(Color.rgb(155, 62, 42));
        footer.setTextSize(12);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer, matchWrap());
    }    private void addSection(LinearLayout root, String text) {
        TextView section = new TextView(this);
        section.setText(text);
        section.setTextSize(17);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(Color.rgb(25, 72, 104));
        section.setPadding(0, dp(18), 0, dp(7));
        root.addView(section);
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private Button makeButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(35, 52, 68));
        button.setBackgroundColor(primary ? Color.rgb(31, 105, 145) : Color.rgb(220, 226, 232));
        return button;
    }

    private TextView valueText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Color.rgb(43, 52, 62));
        view.setBackgroundColor(Color.WHITE);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        return view;
    }

    private ViewGroup.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        params.setMargins(dp(3), dp(2), dp(3), dp(2));
        return params;
    }

    private ViewGroup.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void prepareLogging() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        logDirectory = new File(base, LOG_DIR_NAME);
        if (!logDirectory.exists() && !logDirectory.mkdirs()) {
            logDirectory = getFilesDir();
        }
        sessionLogFile = new File(logDirectory, "n1control-" + fileFormat.format(new Date()) + ".txt");
        environmentReport = buildEnvironmentReport();
        File environmentFile = new File(logDirectory,
                "environment-" + fileFormat.format(new Date()) + ".txt");
        writeTextFile(environmentFile, environmentReport);
        if (logPathView != null) {
            logPathView.setText("日志保存位置：" + logDirectory.getAbsolutePath());
        }
        appendLog("[系统] " + environmentReport.replace("\n", " | "));
        appendLog("[系统] 自动环境报告：" + environmentFile.getAbsolutePath());
        saveLogAndReport(true);
    }

    private String buildEnvironmentReport() {
        StringBuilder report = new StringBuilder();
        report.append("N1Control N7500 低风险控制环境报告\n");
        report.append("生成时间：").append(timeFormat.format(new Date())).append('\n');
        report.append("应用包名：").append(getPackageName()).append('\n');
        report.append("设备厂商：").append(Build.MANUFACTURER).append('\n');
        report.append("设备型号：").append(Build.MODEL).append('\n');
        report.append("设备代号：").append(Build.DEVICE).append('\n');
        report.append("Android：").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        report.append("主 ABI：").append(Build.CPU_ABI).append('\n');
        report.append("安全边界：无 PTT、无音频、无网络、无信道记录写入、无固件升级。\n");
        report.append("写入策略：单写锁；先读原值；写后等待并回读；不一致只回滚一次。\n");
        return report.toString();
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 23 && !hasLocationPermission()) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_PERMISSIONS);
        }
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        appendLog(granted ? "[系统] 定位权限已授权（Android 7 需要它访问蓝牙设备信息）。"
                : "[系统] 定位权限未授权，可能无法读取蓝牙设备列表。");
        refreshPairedDevices();
    }

    private void refreshPairedDevices() {
        if (deviceAdapter == null) return;
        String previousAddress = null;
        BluetoothDevice previous = selectedDevice();
        if (previous != null) previousAddress = previous.getAddress();

        pairedDevices.clear();
        deviceAdapter.clear();
        if (adapter == null) {
            deviceAdapter.add("本机没有蓝牙适配器");
            deviceAdapter.notifyDataSetChanged();
            return;
        }
        if (!adapter.isEnabled()) {
            deviceAdapter.add("蓝牙未开启，请先打开系统蓝牙");
            deviceAdapter.notifyDataSetChanged();
            appendLog("[BT] 蓝牙未开启，无法刷新设备列表。");
            return;
        }
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                pairedDevices.add(device);
                String name = device.getName();
                if (name == null || name.length() == 0) name = "(无名称设备)";
                deviceAdapter.add(name + "  [" + device.getAddress() + "]");
            }
        } catch (Exception e) {
            deviceAdapter.add("读取已配对设备失败");
            appendLog("[BT] 读取已配对设备失败：" + errorText(e));
        }
        if (pairedDevices.isEmpty()) {
            deviceAdapter.add("没有已配对设备，请先在系统蓝牙中配对 N7500");
        } else {
            appendLog("[BT] 已发现 " + pairedDevices.size() + " 个已配对设备。");
        }
        deviceAdapter.notifyDataSetChanged();
        if (previousAddress != null) {
            for (int i = 0; i < pairedDevices.size(); i++) {
                if (previousAddress.equals(pairedDevices.get(i).getAddress())) {
                    deviceSpinner.setSelection(i);
                    break;
                }
            }
        }
    }

    private BluetoothDevice selectedDevice() {
        if (deviceSpinner == null || pairedDevices.isEmpty()) return null;
        int position = deviceSpinner.getSelectedItemPosition();
        if (position < 0 || position >= pairedDevices.size()) return null;
        return pairedDevices.get(position);
    }

    private void connectSelectedDevice() {
        if (adapter == null) {
            showMessage("本机没有蓝牙适配器。");
            return;
        }
        if (!hasLocationPermission()) {
            requestRuntimePermissions();
            showMessage("请先允许定位权限，然后再点连接。");
            return;
        }
        if (!adapter.isEnabled()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
            } catch (Exception e) {
                showMessage("无法请求开启蓝牙：" + errorText(e));
            }
            return;
        }
        BluetoothDevice device = selectedDevice();
        if (device == null) {
            showMessage("没有选择已配对设备，请先刷新或打开系统蓝牙配对。");
            return;
        }
        if (link == null) link = new BluetoothLink(adapter, this);
        autoReadStarted = false;
        connectButton.setEnabled(false);
        disconnectButton.setEnabled(true);
        connectionStatus.setText("状态：正在尝试连接 " + safeDeviceName(device) + "…");
        connectionStatus.setTextColor(Color.rgb(161, 101, 28));
        appendLog("[BT] 开始连接 " + safeDeviceName(device) + " / " + device.getAddress());
        link.connect(device);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (adapter != null && adapter.isEnabled()) {
                refreshPairedDevices();
                connectSelectedDevice();
            } else {
                showMessage("蓝牙未开启，无法连接。");
            }
        }
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            showMessage("无法打开系统蓝牙设置：" + errorText(e));
        }
    }

    private void disconnectRadio() {
        if (activeWrite != null) activeWrite.finish(false, "用户断开蓝牙，操作中止；未自动重试。");
        if (link != null) link.disconnect();
        updateConnectionUi(false);
        connectionStatus.setText("状态：已请求断开");
        connectionStatus.setTextColor(Color.rgb(155, 62, 42));
    }

    private void updateConnectionUi(boolean connected) {
        if (connectButton != null) connectButton.setEnabled(!connected);
        if (disconnectButton != null) disconnectButton.setEnabled(connected || link != null);
        if (connectionStatus != null && !connected && link == null) {
            connectionStatus.setText("状态：未连接");
            connectionStatus.setTextColor(Color.rgb(155, 62, 42));
        }
        updateControls();
    }

    private void updateControls() {
        boolean connected = link != null && link.isConnected();
        boolean idle = !writeGuard.isBusy();
        if (refreshButton != null) refreshButton.setEnabled(connected && idle);
        if (writeVolumeButton != null) writeVolumeButton.setEnabled(connected && idle);
        if (restoreVolumeButton != null) {
            restoreVolumeButton.setEnabled(connected && idle && lastVolumeBeforeWrite >= 0);
        }
        if (toggleScanButton != null) {
            toggleScanButton.setEnabled(connected && idle && lastSettings != null);
        }
        if (restoreSettingsButton != null) {
            restoreSettingsButton.setEnabled(connected && idle && lastSettingsUndo != null);
        }
        if (readChannelButton != null) readChannelButton.setEnabled(connected);
        if (selectAButton != null) {
            selectAButton.setEnabled(connected && idle && lastSettings != null);
        }
        if (selectBButton != null) {
            selectBButton.setEnabled(connected && idle && lastSettings != null);
        }
        if (stabilityButton != null) stabilityButton.setEnabled(connected && idle);
        if (undoView != null) {
            undoView.setText(lastSettingsUndo == null
                    ? "可恢复设置：尚无成功写入记录"
                    : "可恢复设置：" + lastSettingsUndo.label + " 写入前状态");
        }
    }    @Override
    public void onLog(String message) {
        ui.post(() -> appendLog(message));
    }

    @Override
    public void onConnected(String message) {
        ui.post(() -> {
            updateConnectionUi(true);
            connectionStatus.setText("状态：" + message);
            connectionStatus.setTextColor(Color.rgb(31, 115, 76));
            appendLog("[BT] " + message);
            startAutoReadSequence();
        });
    }

    @Override
    public void onConnectFailed(String message) {
        ui.post(() -> {
            updateConnectionUi(false);
            connectionStatus.setText("状态：连接失败");
            connectionStatus.setTextColor(Color.rgb(155, 62, 42));
            appendLog("[BT] 连接失败：" + message.replace("\n", " | "));
            saveLogAndReport(true);
            showMessage("连接失败，详见原始记录。");
        });
    }

    @Override
    public void onDisconnected(String message) {
        ui.post(() -> {
            if (activeWrite != null) activeWrite.finish(false, "蓝牙断开，操作中止；未自动重试。");
            updateConnectionUi(false);
            connectionStatus.setText("状态：蓝牙已断开");
            connectionStatus.setTextColor(Color.rgb(155, 62, 42));
            appendLog("[BT] " + message);
            saveLogAndReport(true);
        });
    }

    @Override
    public void onData(byte[] data) {
        if (data == null || data.length == 0) return;
        ui.post(() -> appendLog("[RX 块] " + data.length + " 字节  " + HexUtil.toHex(data)));
        codec.feed(data, this);
    }

    @Override
    public void onFrame(GaiaCodec.Frame frame) {
        ui.post(() -> handleFrame(frame));
    }

    @Override
    public void onDiscarded(int byteCount) {
        ui.post(() -> {
            appendLog("[RX] 丢弃无法识别的字节：" + byteCount);
            updateStats();
        });
    }

    private void handleFrame(GaiaCodec.Frame frame) {
        appendLog("[RX 帧] " + HexUtil.toHex(frame.raw) + "  |  " + GaiaCodec.summary(frame.body));
        RadioParser.Result result = RadioParser.parse(frame.body);
        appendLog("[解析] " + result.details.replace("\n", " | "));
        if (!result.success) appendLog("[解析] 读取失败；原始帧已完整保留。");
        applyResult(frame.body, result);

        int responseCommand = -1;
        PendingRequest matched = null;
        if (GaiaCodec.isResponse(frame.body)) {
            responseCommand = GaiaCodec.command(frame.body);
            synchronized (pendingRequests) {
                for (int i = 0; i < pendingRequests.size(); i++) {
                    PendingRequest candidate = pendingRequests.get(i);
                    if (!candidate.resolved && candidate.command == responseCommand) {
                        candidate.resolved = true;
                        pendingRequests.remove(i);
                        matched = candidate;
                        break;
                    }
                }
            }
        }
        if (matched != null) {
            requestGuard.finish(responseCommand);
            matched.handler.onResponse(frame.body, result);
        }
        updateStats();
    }

    private void applyResult(byte[] body, RadioParser.Result result) {
        switch (result.type) {
            case RadioParser.TYPE_DEV_INFO:
                deviceInfoView.setText("设备信息\n" + result.details);
                break;
            case RadioParser.TYPE_SETTINGS:
                try {
                    lastSettings = RadioState.SettingsSnapshot.fromResponse(body);
                    settingsView.setText("设置\n" + lastSettings.summary()
                            + "\n原始设置块：" + HexUtil.toHex(lastSettings.writePayload));
                } catch (Exception e) {
                    settingsView.setText("设置解析失败：" + errorText(e));
                    appendLog("[设置] 解析失败：" + errorText(e));
                }
                break;
            case RadioParser.TYPE_HT_STATUS:
                statusView.setText("电台状态\n" + result.details);
                break;
            case RadioParser.TYPE_CHANNEL:
                channelView.setText("信道\n" + result.details);
                break;
            case RadioParser.TYPE_VOLUME:
                try {
                    lastVolume = RadioState.VolumeSnapshot.fromResponse(body);
                    volumeView.setText("音量\n当前：" + lastVolume.volume + " / 15");
                    if (!writeGuard.isBusy()) volumeSeek.setProgress(lastVolume.volume);
                } catch (Exception e) {
                    volumeView.setText("音量解析失败：" + errorText(e));
                    appendLog("[音量] 解析失败：" + errorText(e));
                }
                break;
            case RadioParser.TYPE_NOTIFICATION:
                appendLog("[通知] " + result.details.replace("\n", " | "));
                break;
            default:
                break;
        }
        updateControls();
    }

    private interface ReadHandler {
        void onResponse(byte[] body, RadioParser.Result result);
        void onTimeout();
    }

    private final class PendingRequest {
        final int id;
        final int command;
        final String label;
        final ReadHandler handler;
        boolean resolved;

        PendingRequest(int id, int command, String label, ReadHandler handler) {
            this.id = id;
            this.command = command;
            this.label = label;
            this.handler = handler;
        }
    }

    private void requestRead(int command, byte[] payload, String label, ReadHandler handler) {
        if (link == null || !link.isConnected()) {
            showMessage("蓝牙未连接，不能读取。");
            handler.onTimeout();
            return;
        }
        if (!isAllowedReadCommand(command)) {
            appendLog("[安全] 拒绝非只读请求 0x" + Integer.toHexString(command));
            handler.onTimeout();
            return;
        }
        if (!requestGuard.tryBegin(command)) {
            appendLog("[安全] 同一命令已有请求在途，拒绝重复请求：" + label
                    + " / " + GaiaCodec.commandName(command));
            showMessage("同一命令正在等待回应，请稍后再试。");
            handler.onTimeout();
            return;
        }
        PendingRequest request = new PendingRequest(++requestSequence, command, label, handler);
        synchronized (pendingRequests) {
            pendingRequests.add(request);
        }
        if (!sendFrame(command, payload, label)) {
            synchronized (pendingRequests) {
                pendingRequests.remove(request);
                request.resolved = true;
            }
            requestGuard.finish(command);
            handler.onTimeout();
            return;
        }
        ui.postDelayed(() -> timeoutRequest(request), READ_TIMEOUT_MS);
    }

    private void timeoutRequest(PendingRequest request) {
        synchronized (pendingRequests) {
            if (request.resolved) return;
            request.resolved = true;
            pendingRequests.remove(request);
        }
        requestGuard.finish(request.command);
        appendLog("[超时] " + request.label + " 未在 " + READ_TIMEOUT_MS + " ms 内收到响应。");
        request.handler.onTimeout();
    }

    private boolean sendFrame(int command, byte[] payload, String label) {
        if (link == null || !link.isConnected()) {
            showMessage("蓝牙未连接，不能发送命令。");
            return false;
        }
        if (!isAllowedCommand(command)) {
            appendLog("[安全] 拒绝未列入白名单的命令 0x" + Integer.toHexString(command));
            return false;
        }
        byte[] frame = GaiaCodec.buildFrame(command, payload);
        try {
            link.sendFrame(frame);
            txFrames++;
            txBytes += frame.length;
            appendLog("[TX] " + label + " / " + GaiaCodec.commandName(command)
                    + "  " + HexUtil.toHex(frame));
            updateStats();
            return true;
        } catch (IOException e) {
            appendLog("[TX] 发送失败：" + errorText(e));
            showMessage("命令发送失败：" + errorText(e));
            return false;
        }
    }

    private static boolean isAllowedReadCommand(int command) {
        return command == GaiaCodec.CMD_GET_DEV_INFO
                || command == GaiaCodec.CMD_REGISTER_NOTIFICATION
                || command == GaiaCodec.CMD_READ_SETTINGS
                || command == GaiaCodec.CMD_READ_RF_CH
                || command == GaiaCodec.CMD_GET_HT_STATUS
                || command == GaiaCodec.CMD_GET_VOLUME;
    }

    private static boolean isAllowedCommand(int command) {
        return isAllowedReadCommand(command)
                || command == GaiaCodec.CMD_WRITE_SETTINGS
                || command == GaiaCodec.CMD_SET_VOLUME;
    }

    private void sendRegisterNotification() {
        sendFrame(GaiaCodec.CMD_REGISTER_NOTIFICATION, new byte[] {0x01, 0x0E},
                "注册状态通知");
    }

    private void startAutoReadSequence() {
        if (autoReadStarted) return;
        autoReadStarted = true;
        appendLog("[自动] 开始只读快照读取；不自动写入任何设置。");
        ui.postDelayed(() -> requestRead(GaiaCodec.CMD_GET_DEV_INFO, new byte[] {0x03},
                "读取设备信息", noopReadHandler("设备信息")), 200);
        ui.postDelayed(this::sendRegisterNotification, 500);
        ui.postDelayed(() -> requestRead(GaiaCodec.CMD_READ_SETTINGS, null,
                "读取设置", new ReadHandler() {
                    @Override public void onResponse(byte[] body, RadioParser.Result result) {
                        if (!result.success) return;
                        try {
                            RadioState.SettingsSnapshot snapshot =
                                    RadioState.SettingsSnapshot.fromResponse(body);
                            int index = snapshot.doubleChannel == 2
                                    ? snapshot.channelB : snapshot.channelA;
                            requestRead(GaiaCodec.CMD_READ_RF_CH,
                                    new byte[] {(byte) index}, "读取当前信道", noopReadHandler("信道"));
                        } catch (Exception ignored) {}
                    }
                    @Override public void onTimeout() {}
                }), 800);
        ui.postDelayed(() -> requestRead(GaiaCodec.CMD_GET_HT_STATUS, null,
                "读取电台状态", noopReadHandler("电台状态")), 1200);
        ui.postDelayed(() -> requestRead(GaiaCodec.CMD_GET_VOLUME, null,
                "读取音量", new ReadHandler() {
                    @Override public void onResponse(byte[] body, RadioParser.Result result) {
                        if (!result.success) return;
                        try {
                            RadioState.VolumeSnapshot snapshot =
                                    RadioState.VolumeSnapshot.fromResponse(body);
                            if (!writeGuard.isBusy()) {
                                lastVolumeBeforeWrite = snapshot.volume;
                                updateControls();
                            }
                        } catch (Exception ignored) {}
                    }
                    @Override public void onTimeout() {}
                }), 1600);
    }

    private ReadHandler noopReadHandler(String label) {
        return new ReadHandler() {
            @Override public void onResponse(byte[] body, RadioParser.Result result) {}
            @Override public void onTimeout() {}
        };
    }

    private void refreshSnapshot() {
        if (link == null || !link.isConnected()) {
            showMessage("请先连接电台。");
            return;
        }
        requestRead(GaiaCodec.CMD_GET_DEV_INFO, new byte[] {0x03},
                "读取设备信息", noopReadHandler("设备信息"));
        requestRead(GaiaCodec.CMD_READ_SETTINGS, null,
                "读取设置", noopReadHandler("设置"));
        requestRead(GaiaCodec.CMD_GET_HT_STATUS, null,
                "读取电台状态", noopReadHandler("状态"));
        requestRead(GaiaCodec.CMD_GET_VOLUME, null,
                "读取音量", noopReadHandler("音量"));
        int channel = 0;
        if (lastSettings != null) channel = lastSettings.doubleChannel == 2
                ? lastSettings.channelB : lastSettings.channelA;
        requestRead(GaiaCodec.CMD_READ_RF_CH, new byte[] {(byte) channel},
                "读取当前信道", noopReadHandler("信道"));
    }

    private void readChannelFromInput() {
        int channel;
        try {
            channel = Integer.parseInt(channelEdit.getText().toString().trim());
        } catch (Exception e) {
            showMessage("请输入 0 到 255 之间的信道索引。");
            return;
        }
        if (channel < 0 || channel > 255) {
            showMessage("信道索引超出 0 到 255。");
            return;
        }
        requestRead(GaiaCodec.CMD_READ_RF_CH, new byte[] {(byte) channel},
                "读取信道 " + channel, noopReadHandler("信道"));
    }    private static final class SettingsUndo {
        final RadioState.SettingsSnapshot previous;
        final String label;

        SettingsUndo(RadioState.SettingsSnapshot previous, String label) {
            this.previous = previous;
            this.label = label;
        }
    }

    private final class WriteSession {
        final String label;
        boolean finished;

        WriteSession(String label) { this.label = label; }

        void finish(boolean success, String message) {
            if (finished) return;
            finished = true;
            if (activeWrite == this) activeWrite = null;
            writeGuard.finish();
            setOperationStatus(message, success);
            appendLog("[操作] " + label + "：" + message);
            updateControls();
            saveLogAndReport(true);
        }
    }

    private WriteSession beginWrite(String label) {
        if (link == null || !link.isConnected()) {
            showMessage("蓝牙未连接，不能执行写入。");
            return null;
        }
        if (activeWrite != null || !writeGuard.tryBegin(label)) {
            appendLog("[安全] 拒绝并发写入：" + label + "；当前操作 " + writeGuard.activeLabel());
            showMessage("已有写入操作在进行，本操作未发送任何命令。");
            return null;
        }
        WriteSession session = new WriteSession(label);
        activeWrite = session;
        setOperationStatus(label + "：正在读取原状态…", null);
        updateControls();
        appendLog("[操作] 开始 " + label + "；使用单写锁。");
        return session;
    }

    private void setOperationStatus(String message, Boolean success) {
        if (operationStatusView == null) return;
        operationStatusView.setText(message);
        if (success == null) operationStatusView.setTextColor(Color.rgb(161, 101, 28));
        else if (success) operationStatusView.setTextColor(Color.rgb(31, 115, 76));
        else operationStatusView.setTextColor(Color.rgb(170, 46, 38));
    }

    private void writeSelectedVolume() {
        writeVolumeTarget(volumeSeek.getProgress(), true);
    }

    private void restoreLastVolume() {
        if (lastVolumeBeforeWrite < 0) {
            showMessage("还没有可恢复的音量，请先成功读取或写入一次。");
            return;
        }
        writeVolumeTarget(lastVolumeBeforeWrite, false);
    }

    private void writeVolumeTarget(int target, boolean rememberBaseline) {
        if (target < 0 || target > 15) {
            showMessage("音量必须在 0 到 15 之间。");
            return;
        }
        WriteSession session = beginWrite(rememberBaseline ? "写入音量" : "恢复上次音量");
        if (session == null) return;
        requestRead(GaiaCodec.CMD_GET_VOLUME, null, "读取写入前音量", new ReadHandler() {
            @Override public void onResponse(byte[] body, RadioParser.Result result) {
                if (!result.success) {
                    session.finish(false, "读取写入前音量失败，未发送写入命令。");
                    return;
                }
                int baseline;
                try {
                    baseline = RadioState.VolumeSnapshot.fromResponse(body).volume;
                } catch (Exception e) {
                    session.finish(false, "解析写入前音量失败：" + errorText(e));
                    return;
                }
                if (rememberBaseline) {
                    lastVolumeBeforeWrite = baseline;
                    updateControls();
                }
                if (!sendFrame(GaiaCodec.CMD_SET_VOLUME, new byte[] {(byte) target},
                        "写入音量 " + target)) {
                    session.finish(false, "SET_VOLUME 发送失败，未自动重试。");
                    return;
                }
                ui.postDelayed(() -> verifyVolumeWrite(session, baseline, target),
                        WRITE_SETTLE_MS);
            }
            @Override public void onTimeout() {
                session.finish(false, "读取写入前音量超时，未发送写入命令。");
            }
        });
    }

    private void verifyVolumeWrite(WriteSession session, int baseline, int target) {
        if (session.finished) return;
        requestRead(GaiaCodec.CMD_GET_VOLUME, null, "回读音量", new ReadHandler() {
            @Override public void onResponse(byte[] body, RadioParser.Result result) {
                if (!result.success) {
                    rollbackVolume(session, baseline, "回读音量失败");
                    return;
                }
                try {
                    int observed = RadioState.VolumeSnapshot.fromResponse(body).volume;
                    if (observed == target) {
                        session.finish(true, "音量已回读确认为 " + observed + " / 15。");
                    } else {
                        rollbackVolume(session, baseline,
                                "回读音量为 " + observed + "，目标为 " + target);
                    }
                } catch (Exception e) {
                    rollbackVolume(session, baseline, "回读音量解析失败：" + errorText(e));
                }
            }
            @Override public void onTimeout() { rollbackVolume(session, baseline, "回读音量超时"); }
        });
    }

    private void rollbackVolume(WriteSession session, int baseline, String reason) {
        if (session.finished) return;
        appendLog("[回滚] 音量操作失败：" + reason + "；只尝试恢复原音量 " + baseline + " 一次。");
        if (!sendFrame(GaiaCodec.CMD_SET_VOLUME, new byte[] {(byte) baseline},
                "回滚音量 " + baseline)) {
            session.finish(false, reason + "；回滚命令发送失败，未再次重试。");
            return;
        }
        ui.postDelayed(() -> requestRead(GaiaCodec.CMD_GET_VOLUME, null,
                "回读回滚音量", new ReadHandler() {
                    @Override public void onResponse(byte[] body, RadioParser.Result result) {
                        if (!result.success) {
                            session.finish(false, reason + "；回滚后回读失败。");
                            return;
                        }
                        try {
                            int observed = RadioState.VolumeSnapshot.fromResponse(body).volume;
                            if (observed == baseline) {
                                session.finish(false, reason + "；已回读确认恢复原音量 " + baseline + "。");
                            } else {
                                session.finish(false, reason + "；回滚后音量仍为 " + observed + "。");
                            }
                        } catch (Exception e) {
                            session.finish(false, reason + "；回滚回读解析失败：" + errorText(e));
                        }
                    }
                    @Override public void onTimeout() {
                        session.finish(false, reason + "；回滚后回读超时。");
                    }
                }), WRITE_SETTLE_MS);
    }    private void toggleScan() {
        if (lastSettings == null) {
            showMessage("请先读取设置，再切换扫描状态。");
            return;
        }
        writeSettingsChange(null, null, null, Boolean.valueOf(!lastSettings.scan),
                null, true, "切换扫描状态");
    }

    private void selectDoubleChannel(int mode) {
        if (mode != 1 && mode != 2) {
            showMessage("只支持选择已经保存的信道 A 或 B。");
            return;
        }
        if (lastSettings == null) {
            showMessage("请先读取设置，再选择信道 A/B。");
            return;
        }
        writeSettingsChange(null, null, Integer.valueOf(mode), null, null,
                true, mode == 1 ? "选择信道 A" : "选择信道 B");
    }

    private void writeSettingsChange(Integer newChannelA, Integer newChannelB,
                                     Integer newDoubleChannel, Boolean newScan,
                                     Integer newSquelch, boolean rememberUndo, String label) {
        WriteSession session = beginWrite(label);
        if (session == null) return;
        requestRead(GaiaCodec.CMD_READ_SETTINGS, null, "读取写入前设置", new ReadHandler() {
            @Override public void onResponse(byte[] body, RadioParser.Result result) {
                if (!result.success) {
                    session.finish(false, "读取写入前设置失败，未发送 WRITE_SETTINGS。");
                    return;
                }
                RadioState.SettingsSnapshot baseline;
                try {
                    baseline = RadioState.SettingsSnapshot.fromResponse(body);
                } catch (Exception e) {
                    session.finish(false, "解析写入前设置失败：" + errorText(e));
                    return;
                }
                try {
                    int targetA = newChannelA == null ? baseline.channelA : newChannelA;
                    int targetB = newChannelB == null ? baseline.channelB : newChannelB;
                    int targetDual = newDoubleChannel == null
                            ? baseline.doubleChannel : newDoubleChannel;
                    boolean targetScan = newScan == null ? baseline.scan : newScan;
                    int targetSquelch = newSquelch == null ? baseline.squelch : newSquelch;
                    byte[] expected = baseline.buildPayload(
                            newChannelA, newChannelB, newDoubleChannel, newScan, newSquelch);
                    transmitSettings(session, baseline, expected, targetA, targetB,
                            targetDual, targetScan, targetSquelch, rememberUndo, false);
                } catch (Exception e) {
                    session.finish(false, "构造设置写入失败：" + errorText(e));
                }
            }
            @Override public void onTimeout() {
                session.finish(false, "读取写入前设置超时，未发送 WRITE_SETTINGS。");
            }
        });
    }

    private void transmitSettings(WriteSession session, RadioState.SettingsSnapshot baseline,
                                  byte[] expected, int targetA, int targetB, int targetDual,
                                  boolean targetScan, int targetSquelch,
                                  boolean rememberUndo, boolean clearUndoOnSuccess) {
        if (Arrays.equals(expected, baseline.writePayload)) {
            if (clearUndoOnSuccess) lastSettingsUndo = null;
            session.finish(true, "当前设置已经等于目标状态，未发送写入命令。");
            return;
        }
        if (!sendFrame(GaiaCodec.CMD_WRITE_SETTINGS, expected, "写入设置")) {
            session.finish(false, "WRITE_SETTINGS 发送失败，未自动重试。");
            return;
        }
        ui.postDelayed(() -> readBackSettings(session, baseline, expected, targetA, targetB,
                targetDual, targetScan, targetSquelch, rememberUndo, clearUndoOnSuccess),
                WRITE_SETTLE_MS);
    }

    private void readBackSettings(WriteSession session, RadioState.SettingsSnapshot baseline,
                                  byte[] expected, int targetA, int targetB, int targetDual,
                                  boolean targetScan, int targetSquelch,
                                  boolean rememberUndo, boolean clearUndoOnSuccess) {
        if (session.finished) return;
        requestRead(GaiaCodec.CMD_READ_SETTINGS, null, "回读设置", new ReadHandler() {
            @Override public void onResponse(byte[] body, RadioParser.Result result) {
                if (!result.success) {
                    rollbackSettings(session, baseline, "回读设置命令失败");
                    return;
                }
                try {
                    RadioState.SettingsSnapshot observed =
                            RadioState.SettingsSnapshot.fromResponse(body);
                    boolean fieldsMatch = observed.matches(targetA, targetB, targetDual,
                            targetScan, targetSquelch);
                    boolean payloadMatch = observed.matchesPayload(expected);
                    if (fieldsMatch && payloadMatch) {
                        lastSettings = observed;
                        if (rememberUndo) {
                            lastSettingsUndo = new SettingsUndo(baseline, session.label);
                        }
                        if (clearUndoOnSuccess) lastSettingsUndo = null;
                        session.finish(true, "设置已回读确认；未修改目标字段以外的内容。");
                    } else {
                        rollbackSettings(session, baseline,
                                "回读不一致（字段=" + fieldsMatch + "，完整设置块=" + payloadMatch + "）");
                    }
                } catch (Exception e) {
                    rollbackSettings(session, baseline, "回读设置解析失败：" + errorText(e));
                }
            }
            @Override public void onTimeout() { rollbackSettings(session, baseline, "回读设置超时"); }
        });
    }

    private void rollbackSettings(WriteSession session, RadioState.SettingsSnapshot baseline,
                                  String reason) {
        if (session.finished) return;
        appendLog("[回滚] 设置操作失败：" + reason + "；只尝试恢复写入前完整设置块一次。");
        if (!sendFrame(GaiaCodec.CMD_WRITE_SETTINGS, baseline.writePayload,
                "回滚设置到写入前状态")) {
            session.finish(false, reason + "；回滚命令发送失败，未再次重试。");
            return;
        }
        ui.postDelayed(() -> requestRead(GaiaCodec.CMD_READ_SETTINGS, null,
                "回读回滚设置", new ReadHandler() {
                    @Override public void onResponse(byte[] body, RadioParser.Result result) {
                        if (!result.success) {
                            session.finish(false, reason + "；回滚后回读失败。");
                            return;
                        }
                        try {
                            RadioState.SettingsSnapshot observed =
                                    RadioState.SettingsSnapshot.fromResponse(body);
                            if (observed.matches(baseline.channelA, baseline.channelB,
                                    baseline.doubleChannel, baseline.scan, baseline.squelch)
                                    && observed.matchesPayload(baseline.writePayload)) {
                                session.finish(false, reason + "；已回读确认恢复原设置。");
                            } else {
                                session.finish(false, reason + "；回滚后设置仍与写入前不一致。");
                            }
                        } catch (Exception e) {
                            session.finish(false, reason + "；回滚回读解析失败：" + errorText(e));
                        }
                    }
                    @Override public void onTimeout() {
                        session.finish(false, reason + "；回滚后回读超时。");
                    }
                }), WRITE_SETTLE_MS);
    }

    private void restoreLastSettings() {
        SettingsUndo undo = lastSettingsUndo;
        if (undo == null) {
            showMessage("还没有可恢复的设置，请先成功写入一次设置。");
            return;
        }
        WriteSession session = beginWrite("恢复上次设置");
        if (session == null) return;
        requestRead(GaiaCodec.CMD_READ_SETTINGS, null, "读取当前设置", new ReadHandler() {
            @Override public void onResponse(byte[] body, RadioParser.Result result) {
                if (!result.success) {
                    session.finish(false, "读取当前设置失败，未发送恢复命令。");
                    return;
                }
                try {
                    RadioState.SettingsSnapshot current =
                            RadioState.SettingsSnapshot.fromResponse(body);
                    RadioState.SettingsSnapshot target = undo.previous;
                    transmitSettings(session, current, target.writePayload,
                            target.channelA, target.channelB, target.doubleChannel,
                            target.scan, target.squelch, false, true);
                } catch (Exception e) {
                    session.finish(false, "读取当前设置解析失败：" + errorText(e));
                }
            }
            @Override public void onTimeout() {
                session.finish(false, "读取当前设置超时，未发送恢复命令。");
            }
        });
    }    private void startVolumeStabilityTest() {
        WriteSession session = beginWrite("50 次音量稳定性自检");
        if (session == null) return;
        requestRead(GaiaCodec.CMD_GET_VOLUME, null, "读取自检基准音量", new ReadHandler() {
            @Override public void onResponse(byte[] body, RadioParser.Result result) {
                if (!result.success) {
                    session.finish(false, "读取自检基准音量失败，未发送写入命令。");
                    return;
                }
                try {
                    int baseline = RadioState.VolumeSnapshot.fromResponse(body).volume;
                    appendLog("[自检] 基准音量 " + baseline + "；将连续写入同一值 50 次。");
                    stabilityStep(session, baseline, 0);
                } catch (Exception e) {
                    session.finish(false, "解析自检基准音量失败：" + errorText(e));
                }
            }
            @Override public void onTimeout() {
                session.finish(false, "读取自检基准音量超时。");
            }
        });
    }

    private void stabilityStep(WriteSession session, int target, int completed) {
        if (session.finished) return;
        if (completed >= STABILITY_WRITES) {
            session.finish(true, "50 次同音量写入/回读全部通过；音量仍为 " + target + " / 15。");
            return;
        }
        if (!sendFrame(GaiaCodec.CMD_SET_VOLUME, new byte[] {(byte) target},
                "自检写入音量 " + target + "（" + (completed + 1) + "/50）")) {
            session.finish(false, "自检第 " + (completed + 1) + " 次发送失败，已停止。");
            return;
        }
        setOperationStatus("稳定性自检进行中：" + (completed + 1) + " / 50", null);
        ui.postDelayed(() -> requestRead(GaiaCodec.CMD_GET_VOLUME, null,
                "自检回读音量", new ReadHandler() {
                    @Override public void onResponse(byte[] body, RadioParser.Result result) {
                        if (!result.success) {
                            session.finish(false, "自检第 " + (completed + 1)
                                    + " 次回读失败；未自动重试。");
                            return;
                        }
                        try {
                            int observed = RadioState.VolumeSnapshot.fromResponse(body).volume;
                            if (observed != target) {
                                session.finish(false, "自检第 " + (completed + 1)
                                        + " 次回读音量为 " + observed + "，期望 " + target + "。");
                                return;
                            }
                            stabilityStep(session, target, completed + 1);
                        } catch (Exception e) {
                            session.finish(false, "自检回读解析失败：" + errorText(e));
                        }
                    }
                    @Override public void onTimeout() {
                        session.finish(false, "自检第 " + (completed + 1)
                                + " 次回读超时；未自动重试。");
                    }
                }), WRITE_SETTLE_MS);
    }

    private void appendLog(String message) {
        String line = timeFormat.format(new Date()) + "  " + message + "\n";
        if (logBuffer.length() + line.length() > LOG_LIMIT) {
            int cut = logBuffer.length() - LOG_LIMIT / 2;
            int newline = logBuffer.indexOf("\n", Math.max(0, cut));
            if (newline < 0) newline = logBuffer.length();
            logBuffer.delete(0, Math.min(newline + 1, logBuffer.length()));
        }
        logBuffer.append(line);
        if (logView != null) logView.setText(logBuffer.toString());
    }

    private void updateStats() {
        if (statsView == null) return;
        statsView.setText(String.format(Locale.US,
                "RX 块 %d / %d 字节 / %d 帧 / 丢弃 %d 字节　TX %d 帧 / %d 字节",
                codec.getRxChunks(), codec.getRxBytes(), codec.getRxFrames(),
                codec.getDiscardedBytes(), txFrames, txBytes));
    }

    private void copyLog() {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("N1Control 日志",
                    logBuffer.toString()));
            showMessage("完整日志已复制到剪贴板。");
        } catch (Exception e) {
            showMessage("复制失败：" + errorText(e));
        }
    }

    private boolean saveLogAndReport(boolean quiet) {
        if (sessionLogFile == null) {
            if (!quiet) showMessage("日志文件尚未初始化。");
            return false;
        }
        String content = environmentReport + "\n===== 原始收发记录 =====\n" + logBuffer;
        boolean ok = writeTextFile(sessionLogFile, content);
        if (!quiet) {
            showMessage(ok ? "已保存到 " + sessionLogFile.getAbsolutePath()
                    : "日志保存失败：" + sessionLogFile.getAbsolutePath());
        }
        return ok;
    }

    private boolean writeTextFile(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            return true;
        } catch (Exception e) {
            appendLog("[存储] 写入失败 " + file.getAbsolutePath() + "：" + errorText(e));
            return false;
        }
    }

    private void showMessage(String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } else {
            ui.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        }
    }

    private static String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.length() == 0 ? "(无名称设备)" : name;
        } catch (Exception e) {
            return "(无名称设备)";
        }
    }

    private static String errorText(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPairedDevices();
    }

    @Override
    protected void onDestroy() {
        if (activeWrite != null) activeWrite.finish(false, "应用退出，操作中止。");
        if (link != null) link.shutdown();
        saveLogAndReport(true);
        super.onDestroy();
    }
}