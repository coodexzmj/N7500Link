package com.bi4byf.n7500link.control;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BluetoothLink {
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    interface Listener {
        void onLog(String message);
        void onConnected(String message);
        void onConnectFailed(String message);
        void onDisconnected(String message);
        void onData(byte[] data);
    }

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor();
    private final Object writeLock = new Object();

    private volatile BluetoothSocket socket;
    private volatile BluetoothSocket connectingSocket;
    private volatile boolean connected;
    private volatile boolean manualDisconnect;
    private volatile Thread receiveThread;

    BluetoothLink(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    boolean isConnected() {
        return connected && socket != null;
    }

    void connect(BluetoothDevice device) {
        manualDisconnect = false;
        try {
            connectExecutor.execute(() -> connectWorker(device));
        } catch (Exception e) {
            listener.onConnectFailed("无法启动连接线程：" + errorText(e));
        }
    }

    private void connectWorker(BluetoothDevice device) {
        disconnectInternal(false);
        if (device == null) {
            listener.onConnectFailed("没有选择蓝牙设备。");
            return;
        }
        try {
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                listener.onConnectFailed("设备尚未配对。请先在系统蓝牙设置中完成配对。");
                return;
            }
        } catch (Exception e) {
            listener.onConnectFailed("读取设备配对状态失败：" + errorText(e));
            return;
        }

        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (Exception ignored) {}

        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onConnectFailed("连接被中断。");
            return;
        }

        String deviceName = safeName(device);
        listener.onLog("[BT] 尝试标准 SPP/SDP 连接：" + deviceName + " / " + device.getAddress());
        BluetoothSocket opened = null;
        int openedChannel = -1;
        StringBuilder failures = new StringBuilder();

        try {
            BluetoothSocket standard = device.createRfcommSocketToServiceRecord(SPP_UUID);
            connectingSocket = standard;
            standard.connect();
            connectingSocket = null;
            opened = standard;
            openedChannel = 0;
        } catch (Exception firstError) {
            connectingSocket = null;
            failures.append("SPP/SDP: ").append(errorText(firstError)).append('\n');
            listener.onLog("[BT] 标准 SPP/SDP 失败：" + errorText(firstError));
        }

        if (opened == null && !manualDisconnect) {
            for (int channel = 1; channel <= 12; channel++) {
                if (manualDisconnect) break;
                BluetoothSocket candidate = null;
                try {
                    Method method = device.getClass().getMethod("createRfcommSocket", int.class);
                    candidate = (BluetoothSocket) method.invoke(device, channel);
                    connectingSocket = candidate;
                    candidate.connect();
                    connectingSocket = null;
                    opened = candidate;
                    openedChannel = channel;
                    listener.onLog("[BT] RFCOMM 信道 " + channel + " 连接成功。");
                    break;
                } catch (Exception e) {
                    connectingSocket = null;
                    failures.append("RFCOMM ").append(channel).append(": ")
                            .append(errorText(e)).append('\n');
                    closeQuietly(candidate);
                }
            }
        }

        if (manualDisconnect) {
            closeQuietly(opened);
            listener.onConnectFailed("连接已被用户取消。");
            return;
        }
        if (opened == null) {
            listener.onConnectFailed("所有 SPP/RFCOMM 连接尝试均失败。\n"
                    + trimFailures(failures));
            return;
        }

        socket = opened;
        connected = true;
        String channelText = openedChannel == 0
                ? "SDP 自动信道" : ("RFCOMM " + openedChannel);
        listener.onConnected("已连接 " + deviceName + " (" + device.getAddress()
                + ")，" + channelText);
        startReceiveThread(opened);
    }

    void sendFrame(byte[] frame) throws IOException {
        BluetoothSocket active = socket;
        if (!connected || active == null) throw new IOException("蓝牙未连接");
        synchronized (writeLock) {
            OutputStream output = active.getOutputStream();
            output.write(frame);
            output.flush();
        }
    }

    void disconnect() {
        manualDisconnect = true;
        disconnectInternal(true);
    }

    void shutdown() {
        manualDisconnect = true;
        connectExecutor.shutdownNow();
        disconnectInternal(false);
    }

    private void disconnectInternal(boolean notify) {
        connected = false;
        closeQuietly(connectingSocket);
        connectingSocket = null;
        closeQuietly(socket);
        socket = null;
        Thread rx = receiveThread;
        receiveThread = null;
        if (rx != null && rx != Thread.currentThread()) rx.interrupt();
        if (notify) listener.onDisconnected("蓝牙连接已断开。");
    }

    private void startReceiveThread(BluetoothSocket activeSocket) {
        receiveThread = new Thread(() -> {
            InputStream input;
            try {
                input = activeSocket.getInputStream();
            } catch (Exception e) {
                connected = false;
                listener.onConnectFailed("读取蓝牙输入流失败：" + errorText(e));
                return;
            }

            byte[] chunk = new byte[4096];
            try {
                while (connected && socket == activeSocket
                        && !Thread.currentThread().isInterrupted()) {
                    int count = input.read(chunk);
                    if (count < 0) throw new IOException("远端关闭 RFCOMM 连接");
                    if (count > 0) {
                        byte[] data = new byte[count];
                        System.arraycopy(chunk, 0, data, 0, count);
                        listener.onData(data);
                    }
                }
            } catch (Exception e) {
                boolean wasConnected = connected && socket == activeSocket;
                connected = false;
                if (socket == activeSocket) {
                    closeQuietly(socket);
                    socket = null;
                }
                if (wasConnected && !manualDisconnect) {
                    listener.onDisconnected("蓝牙接收中断：" + errorText(e));
                }
            }
        }, "n1probe-rx");
        receiveThread.start();
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.length() == 0 ? "(无名称设备)" : name;
        } catch (Exception e) {
            return "(无名称设备)";
        }
    }

    private static String errorText(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String trimFailures(StringBuilder failures) {
        String value = failures.toString().trim();
        if (value.length() <= 1600) return value;
        return value.substring(value.length() - 1600);
    }

    private static void closeQuietly(BluetoothSocket target) {
        if (target == null) return;
        try { target.close(); } catch (Exception ignored) {}
    }

    static String channelLabel(int channel) {
        return channel == 0 ? String.format(Locale.US, "SDP") : "RFCOMM %d";
    }
}
