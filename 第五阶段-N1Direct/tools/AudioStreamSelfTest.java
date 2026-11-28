package com.bi4byf.n7500link.direct;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Offline checks for stream framing, escaping, command filters and resampling. */
public final class AudioStreamSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String vectorPath = args.length == 0 ? "tools/sbc-vectors.tsv" : args[0];
        byte[] frame = firstFrame(vectorPath);
        testExact32000(frame, vectorPath);
        testExtendedAndSpecialFrames(frame);
        testCommandFiltering(frame);
        testEscapedStream(frameAt(vectorPath, 1, 0), decodedAt(vectorPath, 1, 0), vectorPath);
        testBadFrameKeepsPrefix(frame);
        testDanglingEscape(frame);
        testOversize();
        testResamplingRates(vectorPath);
        System.out.println("PASS: " + checks + " audio stream checks");
    }

    private static void testExact32000(byte[] frame, String vectorPath) throws Exception {
        RadioAudioStreamParser parser = new RadioAudioStreamParser();
        byte[] wire = packet(0x09, frame);
        byte[] pcm = parser.feed(wire, wire.length);
        check(pcm.length == 256, "32 kHz mono PCM length");
        int[] expected = firstDecoded(vectorPath);
        for (int i = 0; i < expected.length; i++) {
            int actual = littleShort(pcm, i * 2);
            check(actual == expected[i], "exact PCM sample " + i);
        }
        check(parser.decodedFrames() == 1, "one frame decoded");
        check(parser.rejectedFrames() == 0, "no rejected frame");
        check(parser.outputSamples() == 128, "output sample count");
    }

    private static void testCommandFiltering(byte[] frame) {
        RadioAudioStreamParser parser = new RadioAudioStreamParser();
        for (int command : new int[]{0x01, 0x02, 0x03, 0x08, 0x0a}) {
            byte[] wire = packet(command, frame);
            parser.feed(wire, wire.length);
        }
        byte[] packet = specialPacket(0x0008, frame);
        parser.feed(packet, packet.length);
        check(parser.ignoredPackets() == 6, "filtered command count");
        check(parser.decodedFrames() == 0, "filtered command no decode");
        check(parser.outputSamples() == 0, "filtered command no PCM");
    }

    private static void testExtendedAndSpecialFrames(byte[] frame) {
        RadioAudioStreamParser parser = new RadioAudioStreamParser();
        byte[] extended = extendedPacket(0x80, 0x09, frame, false);
        parser.feed(extended, extended.length);
        byte[] checksummed = extendedPacket(0xc0, 0x09, frame, true);
        parser.feed(checksummed, checksummed.length);
        byte[] special = specialPacket(0x0009, frame);
        parser.feed(special, special.length);
        check(parser.audioPackets() == 3, "three audio packet encodings accepted");
        check(parser.decodedFrames() == 3, "three audio frames decoded");
        check(parser.rejectedFrames() == 0, "extended audio frames valid");
        check(parser.outputSamples() == 384, "extended audio sample count");
    }

    private static void testEscapedStream(byte[] frame, int[] expected, String vectorPath) throws Exception {
        RadioAudioStreamParser parser = new RadioAudioStreamParser();
        byte[] first = packet(0x09, frame);
        byte[] second = packet(0x09, frame);
        byte[] combined = concat(first, second);
        int split = first.length - 1;
        int[] actual = new int[512];
        int used = 0;
        byte[] part = parser.feed(combined, split);
        used += appendPcm(actual, used, part);
        part = parser.feed(combined, combined.length - split);
        used += appendPcm(actual, used, part);
        check(used == 256, "two packets output length");
        for (int i = 0; i < expected.length; i++) {
            check(actual[i] == expected[i],
                    "first packet PCM " + i);
        }
        check(parser.packets() == 2, "two packet count");
        check(parser.escapeErrors() == 0, "escaped frame no error");
        check(countByte(frame, 0x7e) > 0, "vector contains escaped delimiter byte");
    }

    private static void testBadFrameKeepsPrefix(byte[] frame) {
        byte[] bad = frame.clone();
        bad[3] ^= 1;
        RadioAudioStreamParser parser = new RadioAudioStreamParser();
        byte[] wire = packet(0x09, concat(frame, bad));
        parser.feed(wire, wire.length);
        check(parser.decodedFrames() == 1, "bad frame keeps decoded prefix");
        check(parser.rejectedFrames() == 1, "bad frame rejected");
        check(parser.outputSamples() == 128, "bad frame emits only prefix PCM");
    }

    private static void testDanglingEscape(byte[] frame) {
        RadioAudioStreamParser parser = new RadioAudioStreamParser();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x7e);
        slip(output, 0x09);
        for (byte value : frame) slip(output, value & 0xff);
        output.write(0x7d);
        output.write(0x7e);
        byte[] wire = output.toByteArray();
        parser.feed(wire, wire.length);
        check(parser.escapeErrors() == 1, "dangling escape counted");
        check(parser.decodedFrames() == 0, "dangling escape drops packet");
        check(parser.outputSamples() == 0, "dangling escape no PCM");
    }

    private static void testOversize() {
        RadioAudioStreamParser parser = new RadioAudioStreamParser();
        byte[] data = new byte[65539];
        data[0] = 0x7e;
        for (int i = 1; i < data.length - 1; i++) data[i] = 0x55;
        data[data.length - 1] = 0x7e;
        parser.feed(data, data.length);
        check(parser.oversizedPackets() == 1, "oversized packet counted");
        check(parser.packets() == 0, "oversized packet not decoded");
        check(parser.outputSamples() == 0, "oversized packet no output");
    }

    private static void testResamplingRates(String vectorPath) throws Exception {
        BufferedReader reader = reader(vectorPath);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] fields = line.split("\t", -1);
                if (!"0".equals(fields[1])) continue;
                int vector = Integer.parseInt(fields[0]);
                if (vector != 1 && vector != 2 && vector != 3 && vector != 5) continue;
                byte[] frame = hex(fields[9]);
                RadioAudioStreamParser parser = new RadioAudioStreamParser();
                byte[] wire = packet(0x09, frame);
                byte[] pcm = parser.feed(wire, wire.length);
                int samples = pcm.length / 2;
                int sourceRate = new int[]{16000, 32000, 44100, 48000}[Integer.parseInt(fields[2])];
                int count = Integer.parseInt(fields[4]) * Integer.parseInt(fields[5]);
                int expectedLow = sourceRate == 16000 ? count * 2 - 2 : (int) Math.floor(count * 32000L / sourceRate);
                int expectedHigh = sourceRate == 16000 ? count * 2 + 2
                        : (int) Math.ceil((count - 1) * 32000.0 / sourceRate) + 1;
                check(samples >= expectedLow && samples <= expectedHigh,
                        "resample count vector=" + vector + " got=" + samples
                                + " range=" + expectedLow + ".." + expectedHigh);
            }
        } finally {
            reader.close();
        }
    }

    private static byte[] packet(int command, byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x7e);
        slip(output, command);
        for (byte value : payload) slip(output, value & 0xff);
        output.write(0x7e);
        return output.toByteArray();
    }

    private static byte[] extendedPacket(int first, int second, byte[] payload,
                                         boolean checksum) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x7e);
        slip(output, first);
        slip(output, second);
        for (byte value : payload) slip(output, value & 0xff);
        if (checksum) {
            slip(output, 0x00);
            slip(output, 0x00);
        }
        output.write(0x7e);
        return output.toByteArray();
    }

    private static byte[] specialPacket(int command, byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x7e);
        slip(output, 0xff);
        slip(output, 0x03);
        slip(output, (command >>> 8) & 0xff);
        slip(output, command & 0xff);
        for (byte value : payload) slip(output, value & 0xff);
        // Non-strict audio decoding accepts command 9 without validating CRC.
        slip(output, 0x00);
        slip(output, 0x00);
        output.write(0x7e);
        return output.toByteArray();
    }

    private static void slip(ByteArrayOutputStream output, int value) {
        if (value == 0x7e || value == 0x7d) {
            output.write(0x7d);
            output.write(value ^ 0x20);
        } else {
            output.write(value);
        }
    }

    private static byte[] frameAt(String path, int wantedVector, int wantedFrame) throws Exception {
        BufferedReader reader = reader(path);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] fields = line.split("\t", -1);
                if (Integer.toString(wantedVector).equals(fields[0])
                        && Integer.toString(wantedFrame).equals(fields[1])) return hex(fields[9]);
            }
        } finally {
            reader.close();
        }
        throw new AssertionError("missing frame");
    }

    private static int[] decodedAt(String path, int wantedVector, int wantedFrame) throws Exception {
        BufferedReader reader = reader(path);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] fields = line.split("\t", -1);
                if (Integer.toString(wantedVector).equals(fields[0])
                        && Integer.toString(wantedFrame).equals(fields[1])) {
                    String[] parts = fields[10].split(",");
                    int[] values = new int[parts.length];
                    for (int i = 0; i < values.length; i++) values[i] = Integer.parseInt(parts[i]);
                    return values;
                }
            }
        } finally {
            reader.close();
        }
        throw new AssertionError("missing decoded frame");
    }

    private static byte[] firstFrame(String path) throws Exception {
        BufferedReader reader = reader(path);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] fields = line.split("\t", -1);
                if ("0".equals(fields[0]) && "0".equals(fields[1])) return hex(fields[9]);
            }
        } finally {
            reader.close();
        }
        throw new AssertionError("missing first frame");
    }

    private static int[] firstDecoded(String path) throws Exception {
        BufferedReader reader = reader(path);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] fields = line.split("\t", -1);
                if ("0".equals(fields[0]) && "0".equals(fields[1])) {
                    String[] parts = fields[10].split(",");
                    int[] values = new int[parts.length];
                    for (int i = 0; i < values.length; i++) values[i] = Integer.parseInt(parts[i]);
                    return values;
                }
            }
        } finally {
            reader.close();
        }
        throw new AssertionError("missing first decoded frame");
    }

    private static BufferedReader reader(String path) throws Exception {
        return new BufferedReader(new InputStreamReader(new FileInputStream(path),
                StandardCharsets.UTF_8));
    }

    private static int appendPcm(int[] target, int offset, byte[] pcm) {
        int count = pcm.length / 2;
        for (int i = 0; i < count; i++) target[offset + i] = littleShort(pcm, i * 2);
        return count;
    }

    private static int littleShort(byte[] data, int offset) {
        int value = (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
        return value >= 32768 ? value - 65536 : value;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static int countByte(byte[] data, int wanted) {
        int count = 0;
        for (byte value : data) if ((value & 0xff) == wanted) count++;
        return count;
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
