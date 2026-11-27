package com.bi4byf.n7500link.probe;

public final class ProtocolSelfTest {
    private static int frames;
    private static int discarded;

    public static void main(String[] args) {
        check(HexUtil.toHex(GaiaCodec.buildFrame(GaiaCodec.CMD_GET_DEV_INFO,
                new byte[] {0x03})).equals("FF 01 00 01 00 02 00 04 03"),
                "GET_DEV_INFO frame encoding");
        check(HexUtil.toHex(GaiaCodec.buildFrame(GaiaCodec.CMD_GET_HT_STATUS, null))
                .equals("FF 01 00 00 00 02 00 14"), "GET_HT_STATUS frame encoding");
        check(HexUtil.toHex(GaiaCodec.buildFrame(GaiaCodec.CMD_READ_RF_CH,
                new byte[] {0x05})).equals("FF 01 00 01 00 02 00 0D 05"),
                "READ_RF_CH frame encoding");

        GaiaCodec codec = new GaiaCodec();
        GaiaCodec.Listener listener = new GaiaCodec.Listener() {
            @Override public void onFrame(GaiaCodec.Frame frame) {
                frames++;
                if (frame.payloadLength == 0) {
                    check(!frame.checksumPresent, "checksum flag");
                }
            }
            @Override public void onDiscarded(int byteCount) { discarded += byteCount; }
        };
        codec.feed(new byte[] {(byte)0xFF}, listener);
        codec.feed(new byte[] {0x01, 0x00, 0x01, 0x00, 0x02, 0x00}, listener);
        codec.feed(new byte[] {0x04, 0x03}, listener);
        check(frames == 1, "fragmented frame reassembly");
        check(codec.getRxChunks() == 3, "rx chunk count");
        check(codec.getRxFrames() == 1, "rx frame count");

        codec.feed(new byte[] {0x12, 0x34, (byte)0xFF, 0x01, 0x00, 0x00,
                0x00, 0x02, 0x00, 0x14}, listener);
        check(frames == 2, "frame after discarded noise");
        check(discarded == 2, "noise byte accounting");
        check(codec.getRxFrames() == 2, "total rx frame count");

        byte[] devBody = new byte[15];
        devBody[1] = 0x02;
        devBody[2] = (byte)0x80;
        devBody[3] = 0x04;
        devBody[5] = 0x01;
        devBody[7] = 0x01;
        devBody[8] = 0x01;
        devBody[10] = 0x01;
        devBody[11] = (byte)0x80;
        devBody[12] = 0x02;
        devBody[13] = 16;
        devBody[14] = 0x10;
        RadioParser.Result result = RadioParser.parse(devBody);
        check(result.success, "device info parse success");
        check(result.details.contains("VR-N7500"), "device product name");
        check(result.details.contains("信道数：16"), "device channel count");
        System.out.println("ProtocolSelfTest PASS: frames=" + frames
                + ", discarded=" + discarded);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError("FAILED: " + label);
        System.out.println("PASS: " + label);
    }
}