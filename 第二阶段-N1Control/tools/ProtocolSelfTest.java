package com.bi4byf.n7500link.control;

import java.util.Arrays;

public final class ProtocolSelfTest {
    private static int passed;

    public static void main(String[] args) {
        try {
            testFrameEncoding();
            testSettingsDecodeAndMutation();
            testSettingsFailures();
            testVolumeParsing();
            testWriteGuard();
            testInFlightRequestGuard();
            testParserWriteResponse();
            System.out.println("PASS: " + passed + " protocol/state tests");
        } catch (Throwable error) {
            System.err.println("FAIL: " + error.getMessage());
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void testFrameEncoding() {
        byte[] setVolume = GaiaCodec.buildFrame(GaiaCodec.CMD_SET_VOLUME, new byte[] {8});
        check(Arrays.equals(setVolume, new byte[] {
                (byte) 0xFF, 0x01, 0x00, 0x01, 0x00, 0x02, 0x00, 0x17, 0x08
        }), "SET_VOLUME 8 帧编码");

        byte[] payload = new byte[] {0x24, 0x35, 0x01, 0x02};
        byte[] write = GaiaCodec.buildFrame(GaiaCodec.CMD_WRITE_SETTINGS, payload);
        check(write.length == 12, "WRITE_SETTINGS 帧长度");
        check(write[3] == 4 && write[6] == 0x00 && write[7] == 0x0B,
                "WRITE_SETTINGS 命令号");
        check(Arrays.equals(Arrays.copyOfRange(write, 8, 12), payload),
                "WRITE_SETTINGS 负载复制");
    }

    private static void testSettingsDecodeAndMutation() {
        byte[] original = new byte[12];
        original[0] = 0x24; // A low=2, B low=4
        original[1] = (byte) 0xB5; // scan=1, dual=3, SQL=5
        original[2] = 0x11;
        original[3] = 0x22;
        original[4] = 0x33;
        original[5] = 0x44;
        original[6] = 0x55;
        original[7] = 0x66;
        original[8] = 0x77;
        original[9] = 0x13; // A high=1, B high=3
        original[10] = 0x01;
        original[11] = 0x02;

        RadioState.SettingsSnapshot snapshot =
                RadioState.SettingsSnapshot.fromResponse(settingsBody(original));
        check(snapshot.channelA == 0x12, "信道 A 解码");
        check(snapshot.channelB == 0x34, "信道 B 解码");
        check(snapshot.doubleChannel == 3, "双信道模式解码");
        check(snapshot.scan, "扫描位解码");
        check(snapshot.squelch == 5, "静噪解码");

        byte[] expected = snapshot.buildPayload(0xAB, 0xCD, 2, false, 7);
        check(expected[0] == (byte) 0xBD, "信道 A/B 低半字节");
        check(expected[1] == 0x27, "扫描位、双信道位和静噪");
        check(expected[9] == (byte) 0xAC, "信道 A/B 高半字节");
        check(Arrays.equals(new byte[] {expected[2], expected[3], expected[4],
                expected[5], expected[6], expected[7], expected[8],
                expected[10], expected[11]}, new byte[] {0x11, 0x22, 0x33,
                0x44, 0x55, 0x66, 0x77, 0x01, 0x02}), "非目标设置字节保持不变");
        RadioState.SettingsSnapshot changed = RadioState.SettingsSnapshot.fromResponse(settingsBody(expected));
        check(changed.matchesPayload(expected), "设置回读完整块匹配");
        check(!snapshot.matchesPayload(expected), "原设置不会被误判为目标设置");
        check(snapshot.buildPayload(null, null, null, null, null).length == original.length,
                "未指定字段时保留完整设置块");
    }

    private static void testSettingsFailures() {
        byte[] body = settingsBody(new byte[12]);
        body[4] = 3;
        expectFailure(() -> RadioState.SettingsSnapshot.fromResponse(body),
                "非零状态设置响应拒绝");
        expectFailure(() -> RadioState.SettingsSnapshot.fromResponse(new byte[16]),
                "设置块不足时拒绝");
        expectFailure(() -> {
            RadioState.SettingsSnapshot snapshot =
                    RadioState.SettingsSnapshot.fromResponse(settingsBody(new byte[12]));
            snapshot.buildPayload(256, null, null, null, null);
        }, "信道索引越界拒绝");
        expectFailure(() -> {
            RadioState.SettingsSnapshot snapshot =
                    RadioState.SettingsSnapshot.fromResponse(settingsBody(new byte[12]));
            snapshot.buildPayload(null, null, 4, null, null);
        }, "双信道模式越界拒绝");
    }

    private static void testVolumeParsing() {
        byte[] body = new byte[] {0x00, 0x02, (byte) 0x80, 0x16, 0x00, 0x08};
        check(RadioState.VolumeSnapshot.fromResponse(body).volume == 8, "音量响应解码");
        body[5] = 16;
        expectFailure(() -> RadioState.VolumeSnapshot.fromResponse(body), "音量越界拒绝");
    }

    private static void testWriteGuard() {
        RadioState.WriteGuard guard = new RadioState.WriteGuard();
        check(guard.tryBegin("first"), "首个写操作可以获得锁");
        check(!guard.tryBegin("second"), "并发写操作被拒绝");
        check(guard.rejectedCount() == 1, "并发拒绝计数");
        guard.finish();
        check(guard.completedCount() == 1, "完成计数");
        check(!guard.isBusy(), "完成后释放锁");
        check(guard.tryBegin("third"), "释放后可以再次写入");
        guard.finish();
        check(guard.completedCount() == 2, "完成计数不重复");
    }

    private static void testInFlightRequestGuard() {
        RadioState.InFlightRequestGuard guard = new RadioState.InFlightRequestGuard();
        check(guard.tryBegin(GaiaCodec.CMD_GET_VOLUME), "首个同命令请求可以登记");
        check(!guard.tryBegin(GaiaCodec.CMD_GET_VOLUME), "重复同命令请求被拒绝");
        check(guard.rejectedCount() == 1, "重复请求拒绝计数");
        check(guard.isPending(GaiaCodec.CMD_GET_VOLUME), "请求在途状态可见");
        guard.finish(GaiaCodec.CMD_GET_VOLUME);
        check(!guard.isPending(GaiaCodec.CMD_GET_VOLUME), "完成或超时后释放命令");
        check(guard.tryBegin(GaiaCodec.CMD_GET_VOLUME), "释放后可以再次请求");
        guard.finish(GaiaCodec.CMD_GET_VOLUME);
    }

    private static void testParserWriteResponse() {
        byte[] writeResponse = new byte[] {0x00, 0x02, (byte) 0x80, 0x0B, 0x00};
        RadioParser.Result result = RadioParser.parse(writeResponse);
        check(result.type == RadioParser.TYPE_OTHER && result.success,
                "写命令响应归类为 OTHER");
        check(result.details.contains("写命令响应"), "写命令响应可辨识");
    }

    private static byte[] settingsBody(byte[] payload) {
        byte[] body = new byte[5 + payload.length];
        body[0] = 0x00;
        body[1] = 0x02;
        body[2] = (byte) 0x80;
        body[3] = 0x0A;
        body[4] = 0x00;
        System.arraycopy(payload, 0, body, 5, payload.length);
        return body;
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + "（没有抛出异常）");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
}