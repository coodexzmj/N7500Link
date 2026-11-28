package com.bi4byf.n7500link.remote;

import android.content.SharedPreferences;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/** Private, non-secret gateway settings. No Bluetooth address is included in API output. */
final class GatewayConfig {
    final String deviceAddress;
    final String deviceLabel;
    final String bindMode;
    final boolean bootEnabled;
    final boolean screenAlwaysOn;

    private GatewayConfig(String deviceAddress, String deviceLabel,
                          String bindMode, boolean bootEnabled, boolean screenAlwaysOn) {
        this.deviceAddress = deviceAddress;
        this.deviceLabel = deviceLabel;
        this.bindMode = bindMode;
        this.bootEnabled = bootEnabled;
        this.screenAlwaysOn = screenAlwaysOn;
    }

    static GatewayConfig load(SharedPreferences preferences) {
        String mode = preferences.getString(GatewayContract.PREF_BIND_MODE,
                GatewayContract.MODE_LOOPBACK);
        if (!GatewayContract.MODE_LOOPBACK.equals(mode)
                && !GatewayContract.MODE_WIFI.equals(mode)
                && !GatewayContract.MODE_TAILSCALE.equals(mode)) {
            mode = GatewayContract.MODE_LOOPBACK;
        }
        return new GatewayConfig(
                preferences.getString(GatewayContract.PREF_DEVICE_ADDRESS, null),
                preferences.getString(GatewayContract.PREF_DEVICE_LABEL, null),
                mode,
                preferences.getBoolean(GatewayContract.PREF_BOOT_ENABLED, false),
                screenAlwaysOn(preferences));
    }

    /**
     * N1 的 Android TV 固件在整机休眠时会断开 Wi-Fi。旧版本首次升级时迁移为
     * 屏幕常亮；0.3.5 再用独立迁移键纠正初始化监听器曾误写的 false 值。
     * 每项迁移只执行一次，用户之后仍可在界面中关闭。
     */
    static boolean screenAlwaysOn(SharedPreferences preferences) {
        if (!preferences.getBoolean(
                GatewayContract.PREF_SCREEN_INITIALIZATION_FIX_MIGRATED, false)) {
            preferences.edit()
                    .putBoolean(GatewayContract.PREF_SCREEN_ALWAYS_ON,
                            GatewayContract.DEFAULT_SCREEN_ALWAYS_ON)
                    .putBoolean(GatewayContract.PREF_SCREEN_DEFAULT_MIGRATED, true)
                    .putBoolean(
                            GatewayContract.PREF_SCREEN_INITIALIZATION_FIX_MIGRATED, true)
                    .apply();
        } else if (!preferences.getBoolean(
                GatewayContract.PREF_SCREEN_DEFAULT_MIGRATED, false)) {
            preferences.edit()
                    .putBoolean(GatewayContract.PREF_SCREEN_ALWAYS_ON,
                            GatewayContract.DEFAULT_SCREEN_ALWAYS_ON)
                    .putBoolean(GatewayContract.PREF_SCREEN_DEFAULT_MIGRATED, true)
                    .apply();
        }
        return preferences.getBoolean(GatewayContract.PREF_SCREEN_ALWAYS_ON,
                GatewayContract.DEFAULT_SCREEN_ALWAYS_ON);
    }
    static void saveSelection(SharedPreferences preferences, String deviceAddress,
                              String deviceLabel, String bindMode, boolean bootEnabled,
                              boolean screenAlwaysOn) {
        SharedPreferences.Editor editor = preferences.edit();
        if (deviceAddress == null || deviceAddress.trim().isEmpty()) {
            editor.remove(GatewayContract.PREF_DEVICE_ADDRESS);
        } else {
            editor.putString(GatewayContract.PREF_DEVICE_ADDRESS, deviceAddress.trim());
        }
        if (deviceLabel == null || deviceLabel.trim().isEmpty()) {
            editor.remove(GatewayContract.PREF_DEVICE_LABEL);
        } else {
            String label = deviceLabel.trim();
            editor.putString(GatewayContract.PREF_DEVICE_LABEL,
                    label.length() > 48 ? label.substring(0, 48) : label);
        }
        editor.putString(GatewayContract.PREF_BIND_MODE, bindMode);
        editor.putBoolean(GatewayContract.PREF_BOOT_ENABLED, bootEnabled);
        editor.putBoolean(GatewayContract.PREF_SCREEN_ALWAYS_ON, screenAlwaysOn);
        editor.putBoolean(GatewayContract.PREF_SCREEN_DEFAULT_MIGRATED, true);
        editor.putBoolean(
                GatewayContract.PREF_SCREEN_INITIALIZATION_FIX_MIGRATED, true);
        editor.apply();
    }

    static InetAddress resolveBindAddress(String mode, SharedPreferences preferences)
            throws IOException {
        if (GatewayContract.MODE_LOOPBACK.equals(mode)
                || mode == null || mode.isEmpty()) {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        }

        InetAddress selected = findAddress(mode);
        if (selected == null) {
            throw new IOException(mode.equals(GatewayContract.MODE_TAILSCALE)
                    ? "TAILSCALE_ADDRESS_NOT_FOUND" : "WIFI_ADDRESS_NOT_FOUND");
        }
        preferences.edit().putString(GatewayContract.PREF_BIND_ADDRESS,
                selected.getHostAddress()).apply();
        return selected;
    }

    static String lastKnownAddress(SharedPreferences preferences) {
        String stored = preferences.getString(GatewayContract.PREF_BIND_ADDRESS, null);
        return stored == null ? "" : stored;
    }

    private static InetAddress findAddress(String mode) throws IOException {
        InetAddress preferred = null;
        InetAddress fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                try {
                    if (!network.isUp() || network.isLoopback()) continue;
                } catch (SocketException ignored) {
                    continue;
                }
                String name = network.getName() == null
                        ? "" : network.getName().toLowerCase(java.util.Locale.US);
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()
                            || address.isMulticastAddress()
                            || address.isAnyLocalAddress()) continue;
                    if (fallback == null) fallback = address;
                    boolean namedForMode;
                    if (GatewayContract.MODE_TAILSCALE.equals(mode)) {
                        namedForMode = name.startsWith("tun") || name.contains("tailscale");
                    } else {
                        namedForMode = name.startsWith("wlan") || name.startsWith("wifi")
                                || name.startsWith("eth") || name.contains("ethernet");
                    }
                    if (namedForMode && preferred == null) preferred = address;
                    if (GatewayContract.MODE_TAILSCALE.equals(mode)
                            && isTailscaleAddress(address) && preferred == null) {
                        preferred = address;
                    }
                }
            }
        } catch (SocketException e) {
            throw new IOException("NETWORK_INTERFACES_UNAVAILABLE", e);
        }
        return preferred != null ? preferred : fallback;
    }

    private static boolean isTailscaleAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return false;
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }
}