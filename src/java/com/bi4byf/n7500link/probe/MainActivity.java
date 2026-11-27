package com.bi4byf.n7500link.probe;

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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements BluetoothLink.Listener, GaiaCodec.Listener {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_ENABLE_BT = 11;
    private static final int LOG_LIMIT = 500000;
    private static final String LOG_DIR_NAME = "N1Probe-logs";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final GaiaCodec codec = new GaiaCodec();
    private final StringBuilder logBuffer = new StringBuilder();
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private final SimpleDateFormat fileFormat = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

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
    private TextView statsView;
    private TextView logView;
    private TextView logPathView;
    private EditText channelEdit;
    private Button connectButton;
    private Button disconnectButton;
    private Button pollButton;
    private Button stopPollButton;

    private File logDirectory;
    private File sessionLogFile;
    private String environmentReport;
    private long txFrames;
    private long txBytes;
    private boolean polling;
    private boolean pendingAutoChannel;
    private int pollTicks;
    private Runnable pollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        adapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        prepareLogging();
        refreshPairedDevices();
        updateConnectionUi(false);
        appendLog("[系统] N1Probe 启动，版本 0.1.0；本程序仅发送只读查询，不包含 PTT、音频或写入命令。");
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
        title.setText("N1Probe · N7500 只读验证");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(24, 36, 48));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("独立蓝牙直连 · 不使用 HT 账号或服务器 · 只读协议探针");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.rgb(82, 96, 110));
        subtitle.setPadding(0, dp(3), 0, dp(12));
        root.addView(subtitle);

        addSection(root, "蓝牙连接");
        deviceSpinner = new Spinner(this);
        deviceSpinner.setMinimumHeight(dp(48));
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);
        root.addView(deviceSpinner, matchWrap());

        LinearLayout refreshRow = horizontalRow();
        Button refreshButton = makeButton("刷新已配对设备", false);
        refreshButton.setOnClickListener(v -> refreshPairedDevices());
        Button pairingButton = makeButton("打开系统蓝牙配对", false);
        pairingButton.setOnClickListener(v -> openBluetoothSettings());
        refreshRow.addView(refreshButton, weighted());
        refreshRow.addView(pairingButton, weighted());
        root.addView(refreshRow);

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

        addSection(root, "只读查询");
        deviceInfoView = valueText("设备信息：尚未读取");
        root.addView(deviceInfoView);
        settingsView = valueText("设置：尚未读取");
        root.addView(settingsView);
        statusView = valueText("电台状态：尚未读取");
        root.addView(statusView);
        volumeView = valueText("音量：尚未读取");
        root.addView(volumeView);
        channelView = valueText("信道：尚未读取");
        root.addView(channelView);

        LinearLayout readRow1 = horizontalRow();
        addActionButton(readRow1, "读设备信息", v -> sendGetDevInfo());
        addActionButton(readRow1, "读设置", v -> sendReadSettings());
        addActionButton(readRow1, "读状态", v -> sendGetStatus());
        root.addView(readRow1);

        LinearLayout readRow2 = horizontalRow();
        addActionButton(readRow2, "读音量", v -> sendGetVolume());
        addActionButton(readRow2, "注册通知", v -> sendRegisterNotification());
        root.addView(readRow2);

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
        Button readChannelButton = makeButton("读取信道", false);
        readChannelButton.setOnClickListener(v -> readChannelFromInput());
        channelRow.addView(readChannelButton, weighted());
        root.addView(channelRow);

        LinearLayout pollRow = horizontalRow();
        pollButton = makeButton("开始只读轮询", false);
        pollButton.setOnClickListener(v -> startPolling());
        stopPollButton = makeButton("停止轮询", false);
        stopPollButton.setOnClickListener(v -> stopPolling());
        pollRow.addView(pollButton, weighted());
        pollRow.addView(stopPollButton, weighted());
        root.addView(pollRow);

        TextView pollHint = valueText("轮询内容：每 5 秒读状态，每 30 秒读音量；均为只读命令。");
        pollHint.setTextSize(12);
        root.addView(pollHint);

        addSection(root, "原始收发记录");
        statsView = valueText("RX 块 0 / 0 字节 / 0 帧 / 丢弃 0 字节　TX 0 帧 / 0 字节");
        statsView.setTypeface(Typeface.MONOSPACE);
        root.addView(statsView);

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
        root.addView(logPathView);

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

        TextView footer = valueText("安全边界：本版本不发送信道写入、音量写入、PTT、音频发射或固件命令。");
        footer.setTextColor(Color.rgb(155, 62, 42));
        footer.setTextSize(12);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer);
    }
    private void addSection(LinearLayout root, String text) {
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

    private void addActionButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button button = makeButton(text, false);
        button.setOnClickListener(listener);
        row.addView(button, weighted());
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
        sessionLogFile = new File(logDirectory, "n1probe-" + fileFormat.format(new Date()) + ".txt");
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
        report.append("N1Probe N7500 只读验证环境报告\n");
        report.append("生成时间：").append(timeFormat.format(new Date())).append('\n');
        report.append("应用包名：").append(getPackageName()).append('\n');
        report.append("设备厂商：").append(Build.MANUFACTURER).append('\n');
        report.append("设备型号：").append(Build.MODEL).append('\n');
        report.append("设备代号：").append(Build.DEVICE).append('\n');
        report.append("Android：").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        report.append("主 ABI：").append(Build.CPU_ABI).append('\n');
        if (Build.VERSION.SDK_INT >= 21) {
            report.append("支持 ABI：").append(joinArray(Build.SUPPORTED_ABIS)).append('\n');
        }
        if (adapter == null) {
            report.append("蓝牙：无适配器\n");
        } else {
            try {
                report.append("蓝牙：").append(adapter.isEnabled() ? "已开启" : "未开启").append('\n');
                report.append("本机蓝牙名称：").append(adapter.getName()).append('\n');
                report.append("本机蓝牙地址：").append(adapter.getAddress()).append('\n');
                report.append("已配对设备数：").append(adapter.getBondedDevices().size()).append('\n');
            } catch (Exception e) {
                report.append("蓝牙信息读取异常：").append(errorText(e)).append('\n');
            }
        }
        report.append("日志目录：").append(logDirectory == null ? "未初始化" : logDirectory.getAbsolutePath()).append('\n');
        return report.toString();
    }

    private static String joinArray(String[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) joined.append(", ");
            joined.append(values[i]);
        }
        return joined.toString();
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
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
        pendingAutoChannel = false;
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
        stopPolling();
        pendingAutoChannel = false;
        if (link != null) link.disconnect();
        updateConnectionUi(false);
        connectionStatus.setText("状态：已请求断开");
        connectionStatus.setTextColor(Color.rgb(155, 62, 42));
    }

    private void updateConnectionUi(boolean connected) {
        if (connectButton != null) connectButton.setEnabled(!connected);
        if (disconnectButton != null) disconnectButton.setEnabled(connected || link != null);
        if (pollButton != null) pollButton.setEnabled(connected);
        if (stopPollButton != null) stopPollButton.setEnabled(polling);
    }
    @Override
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
            stopPolling();
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
        if (!result.success) {
            appendLog("[解析] 读取失败；原始帧已完整保留。");
        }

        switch (result.type) {
            case RadioParser.TYPE_DEV_INFO:
                deviceInfoView.setText("设备信息\n" + result.details);
                break;
            case RadioParser.TYPE_SETTINGS:
                settingsView.setText("设置\n" + result.details);
                if (result.suggestedChannel >= 0) {
                    channelEdit.setText(String.valueOf(result.suggestedChannel));
                    if (pendingAutoChannel) {
                        pendingAutoChannel = false;
                        int channel = result.suggestedChannel;
                        ui.postDelayed(() -> sendReadChannel(channel), 350);
                    }
                }
                break;
            case RadioParser.TYPE_HT_STATUS:
                statusView.setText("电台状态\n" + result.details);
                break;
            case RadioParser.TYPE_CHANNEL:
                channelView.setText("信道\n" + result.details);
                break;
            case RadioParser.TYPE_VOLUME:
                volumeView.setText("音量\n" + result.details);
                break;
            case RadioParser.TYPE_NOTIFICATION:
                appendLog("[通知] " + result.details.replace("\n", " | "));
                break;
            default:
                break;
        }
        updateStats();
    }

    private void startAutoReadSequence() {
        pendingAutoChannel = true;
        appendLog("[自动] 开始只读探测序列。");
        ui.postDelayed(this::sendGetDevInfo, 200);
        ui.postDelayed(this::sendRegisterNotification, 550);
        ui.postDelayed(this::sendReadSettings, 900);
        ui.postDelayed(this::sendGetStatus, 1250);
        ui.postDelayed(this::sendGetVolume, 1600);
        ui.postDelayed(() -> {
            if (pendingAutoChannel && link != null && link.isConnected()) {
                pendingAutoChannel = false;
                appendLog("[自动] 未收到可用信道建议，读取索引 0。");
                sendReadChannel(0);
            }
        }, 3000);
    }

    private void sendGetDevInfo() {
        sendReadCommand(GaiaCodec.CMD_GET_DEV_INFO, new byte[] {0x03}, "设备信息");
    }

    private void sendRegisterNotification() {
        sendReadCommand(GaiaCodec.CMD_REGISTER_NOTIFICATION,
                new byte[] {0x01, 0x0E}, "注册状态通知");
    }

    private void sendReadSettings() {
        sendReadCommand(GaiaCodec.CMD_READ_SETTINGS, null, "读取设置");
    }

    private void sendGetStatus() {
        sendReadCommand(GaiaCodec.CMD_GET_HT_STATUS, null, "读取状态");
    }

    private void sendGetVolume() {
        sendReadCommand(GaiaCodec.CMD_GET_VOLUME, null, "读取音量");
    }

    private void readChannelFromInput() {
        String text = channelEdit.getText().toString().trim();
        int channel;
        try {
            channel = Integer.parseInt(text);
        } catch (Exception e) {
            showMessage("请输入 0 到 255 之间的信道索引。");
            return;
        }
        if (channel < 0 || channel > 255) {
            showMessage("信道索引超出 0 到 255。");
            return;
        }
        sendReadChannel(channel);
    }

    private void sendReadChannel(int channel) {
        sendReadCommand(GaiaCodec.CMD_READ_RF_CH, new byte[] {(byte) channel},
                "读取信道 " + channel);
    }

    private void sendReadCommand(int command, byte[] payload, String label) {
        if (link == null || !link.isConnected()) {
            showMessage("蓝牙未连接，不能发送只读查询。");
            return;
        }
        if (!isAllowedReadCommand(command)) {
            appendLog("[安全] 已拒绝非只读命令 0x" + Integer.toHexString(command));
            return;
        }
        byte[] frame = GaiaCodec.buildFrame(command, payload);
        try {
            link.sendFrame(frame);
            txFrames++;
            txBytes += frame.length;
            appendLog("[TX] " + label + " / " + GaiaCodec.commandName(command)
                    + "  " + HexUtil.toHex(frame));
            updateStats();
        } catch (IOException e) {
            appendLog("[TX] 发送失败：" + errorText(e));
            showMessage("只读命令发送失败：" + errorText(e));
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

    private void startPolling() {
        if (link == null || !link.isConnected()) {
            showMessage("请先连接电台。");
            return;
        }
        polling = true;
        pollTicks = 0;
        if (pollRunnable == null) {
            pollRunnable = () -> {
                if (!polling) return;
                if (link == null || !link.isConnected()) {
                    stopPolling();
                    return;
                }
                pollTicks++;
                sendGetStatus();
                if (pollTicks % 6 == 0) sendGetVolume();
                if (pollTicks % 12 == 0) saveLogAndReport(true);
                ui.postDelayed(pollRunnable, 5000);
            };
        }
        ui.removeCallbacks(pollRunnable);
        appendLog("[轮询] 已开始；每 5 秒读取状态，每 30 秒读取音量。");
        updateConnectionUi(true);
        ui.postDelayed(pollRunnable, 5000);
    }

    private void stopPolling() {
        polling = false;
        if (pollRunnable != null) ui.removeCallbacks(pollRunnable);
        if (stopPollButton != null) stopPollButton.setEnabled(false);
        if (pollButton != null && link != null) pollButton.setEnabled(link.isConnected());
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
            clipboard.setPrimaryClip(ClipData.newPlainText("N1Probe 日志", logBuffer.toString()));
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
        stopPolling();
        if (link != null) link.shutdown();
        saveLogAndReport(true);
        super.onDestroy();
    }
}
