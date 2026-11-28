package com.bi4byf.n7500link.remote;

/** Immutable, nullable radio fields used by the fourth-stage snapshot. */
public final class RadioModels {
    private RadioModels() {}

    public static final class DeviceInfo {
        public final Integer vendorId;
        public final Integer productId;
        public final Integer hardwareVersion;
        public final Integer softwareVersion;
        public final Integer channelCount;
        public final Integer zoneCount;
        public final Integer bandCount;
        public final Boolean fmReceive;
        public final Boolean dmr;
        public final Boolean vfo;

        public DeviceInfo(Integer vendorId, Integer productId, Integer hardwareVersion,
                          Integer softwareVersion, Integer channelCount, Integer zoneCount,
                          Integer bandCount, Boolean fmReceive, Boolean dmr, Boolean vfo) {
            this.vendorId = vendorId;
            this.productId = productId;
            this.hardwareVersion = hardwareVersion;
            this.softwareVersion = softwareVersion;
            this.channelCount = channelCount;
            this.zoneCount = zoneCount;
            this.bandCount = bandCount;
            this.fmReceive = fmReceive;
            this.dmr = dmr;
            this.vfo = vfo;
        }
    }

    public static final class Settings {
        public final Integer channelA;
        public final Integer channelB;
        public final String currentSide;
        public final Boolean scan;
        public final Integer squelch;
        public final String dualMode;

        public Settings(Integer channelA, Integer channelB, String currentSide,
                        Boolean scan, Integer squelch, String dualMode) {
            this.channelA = channelA;
            this.channelB = channelB;
            this.currentSide = currentSide;
            this.scan = scan;
            this.squelch = squelch;
            this.dualMode = dualMode;
        }
    }

    public static final class RadioStatus {
        public final Boolean power;
        public final Boolean transmitting;
        public final Boolean squelchOpen;
        public final Boolean receiving;
        public final Boolean scanning;
        public final Boolean fmBroadcast;
        public final Integer currentChannel;
        public final Integer currentZone;
        public final Integer rssiLevel;

        public RadioStatus(Boolean power, Boolean transmitting, Boolean squelchOpen,
                           Boolean receiving, Boolean scanning, Boolean fmBroadcast,
                           Integer currentChannel, Integer currentZone, Integer rssiLevel) {
            this.power = power;
            this.transmitting = transmitting;
            this.squelchOpen = squelchOpen;
            this.receiving = receiving;
            this.scanning = scanning;
            this.fmBroadcast = fmBroadcast;
            this.currentChannel = currentChannel;
            this.currentZone = currentZone;
            this.rssiLevel = rssiLevel;
        }
    }

    public static final class ChannelInfo {
        public final int id;
        public final String name;
        public final Integer rxFreqHz;
        public final Integer txFreqHz;

        public ChannelInfo(int id, String name, Integer rxFreqHz, Integer txFreqHz) {
            this.id = id;
            this.name = name;
            this.rxFreqHz = rxFreqHz;
            this.txFreqHz = txFreqHz;
        }
    }

    public static final class RadioSnapshot {
        public final DeviceInfo deviceInfo;
        public final Settings settings;
        public final SettingsBlock settingsBlock;
        public final RadioStatus status;
        public final Integer volume;
        public final ChannelInfo channel;

        public RadioSnapshot(DeviceInfo deviceInfo, Settings settings,
                             SettingsBlock settingsBlock, RadioStatus status,
                             Integer volume, ChannelInfo channel) {
            this.deviceInfo = deviceInfo;
            this.settings = settings;
            this.settingsBlock = settingsBlock;
            this.status = status;
            this.volume = volume;
            this.channel = channel;
        }

        public static RadioSnapshot empty() {
            return new RadioSnapshot(null, null, null, null, null, null);
        }

        public RadioSnapshot withDevice(DeviceInfo value) {
            return new RadioSnapshot(value, settings, settingsBlock, status, volume, channel);
        }
        public RadioSnapshot withSettings(Settings value, SettingsBlock block) {
            return new RadioSnapshot(deviceInfo, value, block, status, volume, channel);
        }
        public RadioSnapshot withStatus(RadioStatus value) {
            return new RadioSnapshot(deviceInfo, settings, settingsBlock, value, volume, channel);
        }
        public RadioSnapshot withVolume(Integer value) {
            return new RadioSnapshot(deviceInfo, settings, settingsBlock, status, value, channel);
        }
        public RadioSnapshot withChannel(ChannelInfo value) {
            return new RadioSnapshot(deviceInfo, settings, settingsBlock, status, volume, value);
        }

        public boolean hasAnyData() {
            return deviceInfo != null || settings != null || settingsBlock != null
                    || status != null || volume != null || channel != null;
        }

        public String toRadioJson() {
            StringBuilder out = new StringBuilder(640);
            out.append('{');
            Integer productId = deviceInfo == null ? null : deviceInfo.productId;
            Integer softwareVersion = deviceInfo == null ? null : deviceInfo.softwareVersion;
            out.append("\"productId\":").append(productId == null ? "null"
                    : JsonUtil.quote(String.format(java.util.Locale.US, "0x%04X", productId)));
            out.append(",\"softwareVersion\":").append(softwareVersion == null ? "null"
                    : JsonUtil.quote(String.format(java.util.Locale.US, "0x%04X", softwareVersion)));
            out.append(",\"channelCount\":").append(deviceInfo == null ? "null"
                    : JsonUtil.nullableInt(deviceInfo.channelCount));
            out.append(",\"zoneCount\":").append(deviceInfo == null ? "null"
                    : JsonUtil.nullableInt(deviceInfo.zoneCount));
            out.append(",\"bandCount\":").append(deviceInfo == null ? "null"
                    : JsonUtil.nullableInt(deviceInfo.bandCount));

            out.append(",\"capabilities\":{")
                    .append("\"fmReceive\":").append(deviceInfo == null ? "null"
                            : JsonUtil.nullableBoolean(deviceInfo.fmReceive))
                    .append(",\"dmr\":").append(deviceInfo == null ? "null"
                            : JsonUtil.nullableBoolean(deviceInfo.dmr))
                    .append(",\"vfo\":").append(deviceInfo == null ? "null"
                            : JsonUtil.nullableBoolean(deviceInfo.vfo))
                    .append('}');

            out.append(",\"settings\":{")
                    .append("\"channelA\":").append(settings == null ? "null"
                            : JsonUtil.nullableInt(settings.channelA))
                    .append(",\"channelB\":").append(settings == null ? "null"
                            : JsonUtil.nullableInt(settings.channelB))
                    .append(",\"currentSide\":").append(settings == null ? "null"
                            : JsonUtil.nullableString(settings.currentSide))
                    .append(",\"scan\":").append(settings == null ? "null"
                            : JsonUtil.nullableBoolean(settings.scan))
                    .append(",\"squelch\":").append(settings == null ? "null"
                            : JsonUtil.nullableInt(settings.squelch))
                    .append(",\"dualMode\":").append(settings == null ? "null"
                            : JsonUtil.nullableString(settings.dualMode))
                    .append('}');

            out.append(",\"status\":{")
                    .append("\"power\":").append(status == null ? "null"
                            : JsonUtil.nullableBoolean(status.power))
                    .append(",\"transmitting\":").append(status == null ? "null"
                            : JsonUtil.nullableBoolean(status.transmitting))
                    .append(",\"squelchOpen\":").append(status == null ? "null"
                            : JsonUtil.nullableBoolean(status.squelchOpen))
                    .append(",\"receiving\":").append(status == null ? "null"
                            : JsonUtil.nullableBoolean(status.receiving))
                    .append(",\"scanning\":").append(status == null ? "null"
                            : JsonUtil.nullableBoolean(status.scanning))
                    .append(",\"fmBroadcast\":").append(status == null ? "null"
                            : JsonUtil.nullableBoolean(status.fmBroadcast))
                    .append(",\"currentChannel\":").append(status == null ? "null"
                            : JsonUtil.nullableInt(status.currentChannel))
                    .append(",\"currentZone\":").append(status == null ? "null"
                            : JsonUtil.nullableInt(status.currentZone))
                    .append(",\"rssiLevel\":").append(status == null ? "null"
                            : JsonUtil.nullableInt(status.rssiLevel))
                    .append('}');
            out.append('}');
            return out.toString();
        }

        public String toChannelJson() {
            if (channel == null) return "null";
            return "{\"id\":" + channel.id
                    + ",\"name\":" + JsonUtil.nullableString(channel.name)
                    + ",\"rxMHz\":" + JsonUtil.nullableDouble(channel.rxFreqHz)
                    + ",\"txMHz\":" + JsonUtil.nullableDouble(channel.txFreqHz)
                    + "}";
        }
    }
}
