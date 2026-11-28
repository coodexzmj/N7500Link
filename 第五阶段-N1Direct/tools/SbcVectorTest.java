package com.bi4byf.n7500link.direct;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Exact offline vector check for the fixed-point SBC/mSBC decoder. */
public final class SbcVectorTest {
    private static int frames;
    private static int samples;

    public static void main(String[] args) throws Exception {
        String path = args.length == 0 ? "tools/sbc-vectors.tsv" : args[0];
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(path)), StandardCharsets.UTF_8));
        try {
            SbcDecoder decoder = null;
            int currentVector = -1;
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 11) {
                    throw new AssertionError("bad vector line " + lineNumber
                            + " fields=" + fields.length);
                }
                int vector = Integer.parseInt(fields[0]);
                int frameNumber = Integer.parseInt(fields[1]);
                if (vector != currentVector) {
                    decoder = new SbcDecoder();
                    currentVector = vector;
                    frameNumber = 0;
                }
                check(frameNumber == Integer.parseInt(fields[1]), "frame order");
                byte[] encoded = hex(fields[9]);
                int[] expected = integers(fields[10]);
                SbcDecoder.Frame frame = decoder.parseHeader(encoded, 0, encoded.length);
                check(frame.frequency == Integer.parseInt(fields[2]), "frequency");
                check(frame.mode == Integer.parseInt(fields[3]), "mode");
                check(frame.blocks == Integer.parseInt(fields[4]), "blocks");
                check(frame.subbands == Integer.parseInt(fields[5]), "subbands");
                check(frame.allocation == Integer.parseInt(fields[6]), "allocation");
                check(frame.bitpool == Integer.parseInt(fields[7]), "bitpool");
                check((encoded[0] & 0xff) == (fields[8].equals("1") ? 0xad : 0x9c),
                        "sync");
                int channels = frame.mode == SbcDecoder.MODE_MONO ? 1 : 2;
                int expectedLength = frame.sampleCount() * channels;
                int[] actual = new int[expectedLength];
                int count = decoder.decodeFrame(encoded, 0, encoded.length, actual);
                check(count == expectedLength, "sample count");
                check(actual.length == expected.length, "decoded length vector=" + vector
                        + " frame=" + frameNumber + " actual=" + actual.length
                        + " expected=" + expected.length);
                for (int i = 0; i < expected.length; i++) {
                    if (actual[i] != expected[i]) {
                        throw new AssertionError("decoded mismatch vector=" + vector
                                + " frame=" + frameNumber + " sample=" + i
                                + " expected=" + expected[i] + " actual=" + actual[i]);
                    }
                }
                byte[] corrupt = encoded.clone();
                int crcIndex = expected.length == frame.sampleCount() * 2 ? 3 : 3;
                corrupt[crcIndex] ^= 1;
                try {
                    decoder.parseHeader(corrupt, 0, corrupt.length);
                    throw new AssertionError("CRC corruption accepted vector=" + vector);
                } catch (SbcDecoder.DecodeException expectedError) {
                    check("SBC_CRC".equals(expectedError.code), "CRC rejection");
                }
                frames++;
                samples += expected.length;
            }
        } finally {
            reader.close();
        }
        if (frames != 28 || samples != 3808) {
            throw new AssertionError("unexpected vector totals frames=" + frames
                    + " samples=" + samples);
        }
        System.out.println("PASS: " + frames + " SBC frames, " + samples
                + " decoded samples exact");
    }

    private static byte[] hex(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("hex");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static int[] integers(String value) {
        String[] parts = value.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i]);
        return result;
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
