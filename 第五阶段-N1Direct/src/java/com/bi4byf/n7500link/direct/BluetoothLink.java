package com.bi4byf.n7500link.direct;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The only class allowed to own a Bluetooth socket and its receive loop. */
final class BluetoothLink {
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    interface Listener {
        void onConnected(int epoch, String label);
        void onConnectFailed(int epoch, String code);
        void onDisconnected(int epoch, String code);
        void onData(int epoch, byte[] data);
    }

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor();
    private final Object writeLock = new Object();

    private volatile BluetoothSocket socket;
    private volatile BluetoothSocket connectingSocket;
    private volatile boolean connected;
    private volatile boolean manualDisconnect;
    private volatile int socketEpoch;
    private volatile int desiredEpoch;
    private volatile Thread receiveThread;
    private volatile long rxChunks;
    private volatile long rxBytes;

    BluetoothLink(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    boolean isReady(int epoch) {
        BluetoothSocket active = socket;
        return epoch == socketEpoch && connected && active != null && active.isConnected();
    }

    void connect(BluetoothDevice device, int epoch) {
        desiredEpoch = epoch;
        manualDisconnect = false;
        try {
            connectExecutor.execute(() -> connectWorker(device, epoch));
        } catch (RuntimeException e) {
            listener.onConnectFailed(epoch, "CONNECT_THREAD_START_FAILED");
        }
    }

    void sendFrame(int epoch, byte[] frame) throws IOException {
        BluetoothSocket active = socket;
        if (!isReady(epoch) || active == null) throw new IOException("bluetooth not ready");
        synchronized (writeLock) {
            OutputStream output = active.getOutputStream();
            output.write(frame);
            output.flush();
        }
    }

    void disconnect(int epoch) {
        desiredEpoch = epoch;
        manualDisconnect = true;
        disconnectSocket(false, epoch, "MANUAL_DISCONNECT");
    }

    void shutdown() {
        manualDisconnect = true;
        connectExecutor.shutdownNow();
        disconnectSocket(false, desiredEpoch, "SERVICE_STOPPED");
    }

    private void connectWorker(BluetoothDevice device, int epoch) {
        disconnectSocket(false, epoch, "RECONNECT_REPLACES_SESSION");
        if (manualDisconnect || epoch != desiredEpoch) return;
        if (device == null) {
            listener.onConnectFailed(epoch, "BLUETOOTH_DEVICE_NOT_SELECTED");
            return;
        }
        try {
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                listener.onConnectFailed(epoch, "BLUETOOTH_DEVICE_NOT_PAIRED");
                return;
            }
        } catch (RuntimeException e) {
            listener.onConnectFailed(epoch, "BLUETOOTH_BOND_STATE_UNAVAILABLE");
            return;
        }

        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (RuntimeException ignored) {}

        try {
            Thread.sleep(250L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onConnectFailed(epoch, "BLUETOOTH_CONNECT_INTERRUPTED");
            return;
        }
        if (manualDisconnect || epoch != desiredEpoch) return;

        BluetoothSocket opened = openSocket(device, epoch);
        if (manualDisconnect || epoch != desiredEpoch) {
            closeQuietly(opened);
            return;
        }
        if (opened == null) {
            listener.onConnectFailed(epoch, "BLUETOOTH_CONNECT_FAILED");
            return;
        }

        socket = opened;
        socketEpoch = epoch;
        connected = true;
        listener.onConnected(epoch, safeName(device));
        startReceiveThread(opened, epoch);
    }

    private BluetoothSocket openSocket(BluetoothDevice device, int epoch) {
        BluetoothSocket opened = null;
        try {
            BluetoothSocket standard = device.createRfcommSocketToServiceRecord(SPP_UUID);
            connectingSocket = standard;
            standard.connect();
            connectingSocket = null;
            opened = standard;
        } catch (Exception firstError) {
            connectingSocket = null;
            closeQuietly(opened);
        }
        if (opened != null || manualDisconnect || epoch != desiredEpoch) return opened;

        for (int channel = 1; channel <= 12; channel++) {
            if (manualDisconnect || epoch != desiredEpoch) break;
            BluetoothSocket candidate = null;
            try {
                Method method = device.getClass().getMethod("createRfcommSocket", int.class);
                candidate = (BluetoothSocket) method.invoke(device, channel);
                connectingSocket = candidate;
                candidate.connect();
                connectingSocket = null;
                opened = candidate;
                break;
            } catch (Exception ignored) {
                connectingSocket = null;
                closeQuietly(candidate);
            }
        }
        return opened;
    }

    private void startReceiveThread(BluetoothSocket activeSocket, int epoch) {
        rxChunks = 0L;
        rxBytes = 0L;
        receiveThread = new Thread(() -> {
            InputStream input;
            try {
                input = activeSocket.getInputStream();
            } catch (Exception e) {
                if (socketEpoch == epoch && connected) {
                    connected = false;
                    listener.onDisconnected(epoch, "BLUETOOTH_INPUT_OPEN_FAILED");
                }
                return;
            }
            byte[] chunk = new byte[4096];
            try {
                while (connected && socket == activeSocket && desiredEpoch == epoch
                        && !Thread.currentThread().isInterrupted()) {
                    int count = input.read(chunk);
                    if (count < 0) throw new IOException("remote closed");
                    if (count > 0) {
                        rxChunks++;
                        rxBytes += count;
                        byte[] data = new byte[count];
                        System.arraycopy(chunk, 0, data, 0, count);
                        listener.onData(epoch, data);
                    }
                }
            } catch (Exception ignored) {
                boolean wasCurrent = connected && socket == activeSocket && socketEpoch == epoch;
                if (wasCurrent) {
                    connected = false;
                    closeQuietly(activeSocket);
                    if (socket == activeSocket) socket = null;
                    if (!manualDisconnect) {
                        listener.onDisconnected(epoch, "BLUETOOTH_RECEIVE_INTERRUPTED");
                    }
                }
            }
        }, "n1gateway-bt-rx");
        receiveThread.start();
    }

    long getRxChunks() { return rxChunks; }
    long getRxBytes() { return rxBytes; }

    private void disconnectSocket(boolean notify, int epoch, String code) {
        connected = false;
        closeQuietly(connectingSocket);
        connectingSocket = null;
        closeQuietly(socket);
        socket = null;
        Thread rx = receiveThread;
        receiveThread = null;
        if (rx != null && rx != Thread.currentThread()) rx.interrupt();
        if (notify) listener.onDisconnected(epoch, code);
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            if (name == null || name.trim().isEmpty()) return "N7500";
            String value = name.trim();
            return value.length() > 48 ? value.substring(0, 48) : value;
        } catch (RuntimeException e) {
            return "N7500";
        }
    }

    private static void closeQuietly(BluetoothSocket target) {
        if (target == null) return;
        try { target.close(); } catch (Exception ignored) {}
    }
}
