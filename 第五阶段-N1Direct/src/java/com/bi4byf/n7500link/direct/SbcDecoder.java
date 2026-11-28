package com.bi4byf.n7500link.direct;

/**
 * Fixed-point SBC and mSBC decoder.
 *
 * Ported from the Apache-2.0 licensed fixed-point codec shared by the HTCommander
 * project. The decoder returns interleaved left/right samples for one frame;
 * mono frames return left/right pairs with both samples equal so callers can
 * uniformly downmix and resample.
 */
final class SbcDecoder {
    static final int MODE_MONO = 0;
    static final int MODE_DUAL = 1;
    static final int MODE_STEREO = 2;
    static final int MODE_JOINT = 3;

    private static final int[] FREQUENCIES = {16000, 32000, 44100, 48000};
    private static final int[] RANGE_SCALE = {
            0x0FFFFFFF, 0x05555556, 0x02492492, 0x01111111,
            0x00842108, 0x00410410, 0x00204081, 0x00101010,
            0x00080402, 0x00040100, 0x00020040, 0x00010010,
            0x00008004, 0x00004001, 0x00002000, 0x00001000
    };
    private static final int[] COS8 = {8192, 7568, 5793, 3135};
    private static final int[] COS16 = {8192, 8035, 7568, 6811, 5793, 4551, 3135, 1598};
    private static final int[][] LOUDNESS_OFFSET4 = {
            {-1, 0, 0, 0}, {-2, 0, 0, 1}, {-2, 0, 0, 1}, {-2, 0, 0, 1}
    };
    private static final int[][] LOUDNESS_OFFSET8 = {
            {-2, 0, 0, 0, 0, 0, 0, 1},
            {-3, 0, 0, 0, 0, 0, 1, 2},
            {-4, 0, 0, 0, 0, 0, 1, 2},
            {-4, 0, 0, 0, 0, 0, 1, 2}
    };
    private static final int[][] DEC_WINDOW4 = {
            {0, -126, -358, -848, -4443, -9644, 4443, -848, 358, -126, 0, -126, -358, -848, -4443, -9644, 4443, -848, 358, -126},
            {-18, -128, -670, -201, -6389, -9235, 2544, -1055, 100, -90, -18, -128, -670, -201, -6389, -9235, 2544, -1055, 100, -90},
            {-49, -61, -946, 944, -8082, -8082, 944, -946, -61, -49, -49, -61, -946, 944, -8082, -8082, 944, -946, -61, -49},
            {-90, 100, -1055, 2544, -9235, -6389, -201, -670, -128, -18, -90, 100, -1055, 2544, -9235, -6389, -201, -670, -128, -18}
    };
    private static final int[][] DEC_WINDOW8 = {
            {0, -132, -371, -848, -4456, -9631, 4456, -848, 371, -132, 0, -132, -371, -848, -4456, -9631, 4456, -848, 371, -132},
            {-10, -138, -526, -580, -5438, -9528, 3486, -1004, 229, -117, -10, -138, -526, -580, -5438, -9528, 3486, -1004, 229, -117},
            {-22, -131, -685, -192, -6395, -9224, 2561, -1063, 108, -97, -22, -131, -685, -192, -6395, -9224, 2561, -1063, 108, -97},
            {-36, -106, -835, 322, -7287, -8734, 1711, -1042, 12, -75, -36, -106, -835, 322, -7287, -8734, 1711, -1042, 12, -75},
            {-54, -59, -960, 959, -8078, -8078, 959, -960, -59, -54, -54, -59, -960, 959, -8078, -8078, 959, -960, -59, -54},
            {-75, 12, -1042, 1711, -8734, -7287, 322, -835, -106, -36, -75, 12, -1042, 1711, -8734, -7287, 322, -835, -106, -36},
            {-97, 108, -1063, 2561, -9224, -6395, -192, -685, -131, -22, -97, 108, -1063, 2561, -9224, -6395, -192, -685, -131, -22},
            {-117, 229, -1004, 3486, -9528, -5438, -580, -526, -138, -10, -117, 229, -1004, 3486, -9528, -5438, -580, -526, -138, -10}
    };
    private static final int[] CRC8_TABLE = {
            0x00, 0x1d, 0x3a, 0x27, 0x74, 0x69, 0x4e, 0x53, 0xe8, 0xf5, 0xd2, 0xcf, 0x9c, 0x81, 0xa6, 0xbb,
            0xcd, 0xd0, 0xf7, 0xea, 0xb9, 0xa4, 0x83, 0x9e, 0x25, 0x38, 0x1f, 0x02, 0x51, 0x4c, 0x6b, 0x76,
            0x87, 0x9a, 0xbd, 0xa0, 0xf3, 0xee, 0xc9, 0xd4, 0x6f, 0x72, 0x55, 0x48, 0x1b, 0x06, 0x21, 0x3c,
            0x4a, 0x57, 0x70, 0x6d, 0x3e, 0x23, 0x04, 0x19, 0xa2, 0xbf, 0x98, 0x85, 0xd6, 0xcb, 0xec, 0xf1,
            0x13, 0x0e, 0x29, 0x34, 0x67, 0x7a, 0x5d, 0x40, 0xfb, 0xe6, 0xc1, 0xdc, 0x8f, 0x92, 0xb5, 0xa8,
            0xde, 0xc3, 0xe4, 0xf9, 0xaa, 0xb7, 0x90, 0x8d, 0x36, 0x2b, 0x0c, 0x11, 0x42, 0x5f, 0x78, 0x65,
            0x94, 0x89, 0xae, 0xb3, 0xe0, 0xfd, 0xda, 0xc7, 0x7c, 0x61, 0x46, 0x5b, 0x08, 0x15, 0x32, 0x2f,
            0x59, 0x44, 0x63, 0x7e, 0x2d, 0x30, 0x17, 0x0a, 0xb1, 0xac, 0x8b, 0x96, 0xc5, 0xd8, 0xff, 0xe2,
            0x26, 0x3b, 0x1c, 0x01, 0x52, 0x4f, 0x68, 0x75, 0xce, 0xd3, 0xf4, 0xe9, 0xba, 0xa7, 0x80, 0x9d,
            0xeb, 0xf6, 0xd1, 0xcc, 0x9f, 0x82, 0xa5, 0xb8, 0x03, 0x1e, 0x39, 0x24, 0x77, 0x6a, 0x4d, 0x50,
            0xa1, 0xbc, 0x9b, 0x86, 0xd5, 0xc8, 0xef, 0xf2, 0x49, 0x54, 0x73, 0x6e, 0x3d, 0x20, 0x07, 0x1a,
            0x6c, 0x71, 0x56, 0x4b, 0x18, 0x05, 0x22, 0x3f, 0x84, 0x99, 0xbe, 0xa3, 0xf0, 0xed, 0xca, 0xd7,
            0x35, 0x28, 0x0f, 0x12, 0x41, 0x5c, 0x7b, 0x66, 0xdd, 0xc0, 0xe7, 0xfa, 0xa9, 0xb4, 0x93, 0x8e,
            0xf8, 0xe5, 0xc2, 0xdf, 0x8c, 0x91, 0xb6, 0xab, 0x10, 0x0d, 0x2a, 0x37, 0x64, 0x79, 0x5e, 0x43,
            0xb2, 0xaf, 0x88, 0x95, 0xc6, 0xdb, 0xfc, 0xe1, 0x5a, 0x47, 0x60, 0x7d, 0x2e, 0x33, 0x14, 0x09,
            0x7f, 0x62, 0x45, 0x58, 0x0b, 0x16, 0x31, 0x2c, 0x97, 0x8a, 0xad, 0xb0, 0xe3, 0xfe, 0xd9, 0xc4
    };

    static final class Frame {
        int frequency;
        int mode;
        int allocation;
        int blocks;
        int subbands;
        int bitpool;

        int channels() { return mode == MODE_MONO ? 1 : 2; }
        int sampleCount() { return blocks * subbands; }
        int size() {
            return 4 + (4 * subbands * channels() + blocks * bitpool
                    * (mode == MODE_DUAL ? 2 : 1) + (mode == MODE_JOINT ? subbands : 0)
                    + 7) / 8;
        }
        int sampleRate() { return FREQUENCIES[frequency]; }
        boolean valid() {
            int maxBits = 16 * subbands * blocks * channels() - 32
                    - 4 * subbands * channels() - (mode == MODE_JOINT ? subbands : 0);
            int perChannel = Math.min(maxBits / (blocks * (mode == MODE_DUAL ? 2 : 1)),
                    (mode >= MODE_STEREO ? 32 : 16) * subbands);
            return bitpool <= perChannel;
        }
    }

    static final class DecodeException extends Exception {
        final String code;
        DecodeException(String code) { super(code); this.code = code; }
    }

    private static final class Reader {
        final byte[] data;
        final int end;
        int position;
        int buffer;
        int count;

        Reader(byte[] data, int offset, int length) {
            this.data = data;
            this.position = offset + 4;
            this.end = offset + length;
        }

        int get(int bits) throws DecodeException {
            if (bits <= 0 || bits > 16) throw new DecodeException("SBC_BIT_WIDTH");
            while (count < bits) {
                if (position >= end) throw new DecodeException("SBC_TRUNCATED");
                buffer = (buffer << 8) | (data[position++] & 0xff);
                count += 8;
            }
            count -= bits;
            return (buffer >>> count) & ((1 << bits) - 1);
        }
    }

    private static final class DecoderState {
        int index;
        final int[][][] v = new int[2][8][10];
    }

    private DecoderState[] states = {new DecoderState(), new DecoderState()};

    void reset() {
        states = new DecoderState[]{new DecoderState(), new DecoderState()};
    }

    Frame parseHeader(byte[] data, int offset, int available) throws DecodeException {
        if (available <= 0) throw new DecodeException("SBC_TRUNCATED");
        if (data[offset] != (byte) 0x9c && data[offset] != (byte) 0xad) {
            throw new DecodeException("SBC_SYNC");
        }
        if (available < 4) throw new DecodeException("SBC_TRUNCATED");
        Frame frame = new Frame();
        if (data[offset] == (byte) 0xad) {
            frame.frequency = 0;
            frame.mode = MODE_MONO;
            frame.allocation = 0;
            frame.blocks = 15;
            frame.subbands = 8;
            frame.bitpool = 26;
        } else {
            int b1 = data[offset + 1] & 0xff;
            frame.frequency = b1 >> 6;
            frame.blocks = (1 + ((b1 >> 4) & 3)) * 4;
            frame.mode = (b1 >> 2) & 3;
            frame.allocation = (b1 >> 1) & 1;
            frame.subbands = (1 + (b1 & 1)) * 4;
            frame.bitpool = data[offset + 2] & 0xff;
        }
        if (!frame.valid()) throw new DecodeException("SBC_HEADER");
        if (available < frame.size()) throw new DecodeException("SBC_TRUNCATED");
        if (crc(frame, data, offset) != (data[offset + 3] & 0xff)) {
            throw new DecodeException("SBC_CRC");
        }
        return frame;
    }

    /**
     * Decodes one frame into PCM. Mono frames return one sample per position;
     * stereo-capable modes return interleaved left/right samples. The return
     * value is the number of samples written, not sample pairs.
     */
    int decodeFrame(byte[] data, int offset, int available, int[] output)
            throws DecodeException {
        Frame frame = parseHeader(data, offset, available);
        Reader reader = new Reader(data, offset, frame.size());
        int joint = 0;
        if (frame.mode == MODE_JOINT) {
            int mask = reader.get(frame.subbands);
            for (int i = 0; i < frame.subbands - 1; i++) {
                joint |= ((mask >>> (frame.subbands - 1 - i)) & 1) << i;
            }
        }
        int[][] scf = new int[2][8];
        int[][] bits = new int[2][8];
        int[] scale = new int[2];
        int[][] samples = new int[2][128];
        int channels = frame.channels();
        for (int ch = 0; ch < channels; ch++) {
            for (int sb = 0; sb < frame.subbands; sb++) {
                scf[ch][sb] = reader.get(4);
            }
        }
        allocate(frame, scf, bits, 0, frame.mode >= MODE_STEREO ? 2 : 1);
        if (frame.mode == MODE_DUAL) {
            allocate(frame, scf, bits, 1, 1);
        }
        for (int ch = 0; ch < channels; ch++) {
            int maximum = 0;
            for (int sb = 0; sb < frame.subbands; sb++) {
                maximum = Math.max(maximum, scf[ch][sb] + ((joint >>> sb) & 1));
            }
            scale[ch] = 14 - maximum;
        }
        if (frame.mode == MODE_JOINT) {
            scale[0] = scale[1] = Math.min(scale[0], scale[1]);
        }
        for (int block = 0; block < frame.blocks; block++) {
            for (int ch = 0; ch < channels; ch++) {
                for (int sb = 0; sb < frame.subbands; sb++) {
                    int n = bits[ch][sb];
                    if (n == 0) continue;
                    long value = (reader.get(n) * 2L + 1L) * RANGE_SCALE[n - 1];
                    samples[ch][block * frame.subbands + sb] =
                            wrap(asr(value - (1L << 28),
                                    28 - (scf[ch][sb] + 1 + scale[ch])));
                }
            }
        }
        for (int sb = 0; sb < frame.subbands; sb++) {
            if (((joint >>> sb) & 1) == 0) continue;
            for (int block = 0; block < frame.blocks; block++) {
                int index = block * frame.subbands + sb;
                int a = samples[0][index];
                int b = samples[1][index];
                samples[0][index] = wrap(a + b);
                samples[1][index] = wrap(a - b);
            }
        }
        for (int block = 0; block < frame.blocks; block++) {
            int count = frame.subbands;
            int[] left = new int[count];
            int[] right = new int[count];
            synthesize(states[0], frame.subbands, slice(samples[0], block, count),
                    scale[0], left);
            if (channels == 2) {
                synthesize(states[1], frame.subbands, slice(samples[1], block, count),
                        scale[1], right);
                for (int i = 0; i < count; i++) {
                    output[(block * count + i) * 2] = left[i];
                    output[(block * count + i) * 2 + 1] = right[i];
                }
            } else {
                for (int i = 0; i < count; i++) {
                    output[block * count + i] = left[i];
                }
            }
        }
        return frame.sampleCount() * (channels == 2 ? 2 : 1);
    }

    int frameSize(byte[] data, int offset, int available) throws DecodeException {
        return parseHeader(data, offset, available).size();
    }

    private static int[] slice(int[] values, int block, int count) {
        int[] result = new int[count];
        System.arraycopy(values, block * count, result, 0, count);
        return result;
    }

    private static void allocate(Frame frame, int[][] scf, int[][] bits,
                                  int channelOffset, int channels) {
        int[] offsets = frame.subbands == 4
                ? LOUDNESS_OFFSET4[frame.frequency] : LOUDNESS_OFFSET8[frame.frequency];
        int[][] needs = new int[2][8];
        int maxNeed = 0;
        for (int ch = 0; ch < channels; ch++) {
            for (int sb = 0; sb < frame.subbands; sb++) {
                int n = scf[ch + channelOffset][sb];
                if (frame.allocation == 0) {
                    n = n != 0 ? n - offsets[sb] : -5;
                    if (n > 0) n /= 2;
                }
                needs[ch][sb] = n;
                maxNeed = Math.max(maxNeed, n);
            }
        }
        int count = 0;
        int slice = maxNeed + 1;
        for (int bitCount = 0; bitCount < frame.bitpool;) {
            int bandSlice = slice--;
            count = bitCount;
            for (int ch = 0; ch < channels; ch++) {
                for (int sb = 0; sb < frame.subbands; sb++) {
                    int n = needs[ch][sb];
                    bitCount += (n >= bandSlice && n < bandSlice + 15 ? 1 : 0)
                            + (n == bandSlice ? 1 : 0);
                }
            }
        }
        for (int ch = 0; ch < channels; ch++) {
            for (int sb = 0; sb < frame.subbands; sb++) {
                int n = needs[ch][sb] - slice;
                bits[ch + channelOffset][sb] = n < 2 ? 0 : Math.min(n, 16);
            }
        }
        for (int sb = 0; sb < frame.subbands && count < frame.bitpool; sb++) {
            for (int ch = 0; ch < channels && count < frame.bitpool; ch++) {
                int b = bits[ch + channelOffset][sb];
                int n = b > 0 && b < 16 ? 1
                        : (needs[ch][sb] == slice + 1 && frame.bitpool > count + 1 ? 2 : 0);
                bits[ch + channelOffset][sb] += n;
                count += n;
            }
        }
        for (int sb = 0; sb < frame.subbands && count < frame.bitpool; sb++) {
            for (int ch = 0; ch < channels && count < frame.bitpool; ch++) {
                if (bits[ch + channelOffset][sb] < 16) {
                    bits[ch + channelOffset][sb]++;
                    count++;
                }
            }
        }
    }

    private static void synthesize(DecoderState state, int subbands, int[] input,
                                   int scale, int[] output) {
        int idx = state.index != 0 ? 10 - state.index : 0;
        int odd = idx & 1;
        int[][] o = state.v[odd];
        int[][] p = state.v[1 - odd];
        int shift = 12 + scale;
        long[] a = new long[4];
        long[] b = new long[4];
        if (subbands == 4) {
            long s03 = asr(input[0] + (long) input[3], 1);
            long d03 = asr(input[0] - (long) input[3], 1);
            long s12 = asr(input[1] + (long) input[2], 1);
            long d12 = asr(input[1] - (long) input[2], 1);
            a[0] = (s03 - s12) * COS8[2];
            b[1] = -(s03 + s12) * 8192L;
            a[1] = d03 * COS8[3] - d12 * COS8[1];
            b[0] = -d03 * COS8[1] - d12 * COS8[3];
        } else {
            long s07 = asr(input[0] + (long) input[7], 1);
            long d07 = asr(input[0] - (long) input[7], 1);
            long s16 = asr(input[1] + (long) input[6], 1);
            long d16 = asr(input[1] - (long) input[6], 1);
            long s25 = asr(input[2] + (long) input[5], 1);
            long d25 = asr(input[2] - (long) input[5], 1);
            long s34 = asr(input[3] + (long) input[4], 1);
            long d34 = asr(input[3] - (long) input[4], 1);
            a[0] = ((s07 + s34) - (s25 + s16)) * COS16[4];
            b[3] = (-(s07 + s34) - (s25 + s16)) * 8192L;
            a[2] = (s07 - s34) * COS16[6] + (s25 - s16) * COS16[2];
            b[1] = (s34 - s07) * COS16[2] + (s25 - s16) * COS16[6];
            a[1] = d07 * COS16[5] - d16 * COS16[1]
                    + d25 * COS16[7] + d34 * COS16[3];
            b[2] = -d07 * COS16[1] - d16 * COS16[3]
                    - d25 * COS16[5] - d34 * COS16[7];
            a[3] = d07 * COS16[7] - d16 * COS16[5]
                    + d25 * COS16[3] - d34 * COS16[1];
            b[0] = -d07 * COS16[3] + d16 * COS16[7]
                    + d25 * COS16[1] + d34 * COS16[5];
        }
        for (int i = 0; i < subbands / 2; i++) {
            a[i] = rounded(a[i], shift);
            b[i] = rounded(b[i], shift);
        }
        o[0][idx] = sat(a[0]);
        p[0][idx] = sat(-a[0]);
        for (int i = 1; i < subbands / 2; i++) {
            o[i][idx] = sat(a[i]);
            o[subbands - i][idx] = sat(-a[i]);
        }
        o[subbands / 2][idx] = 0;
        for (int i = 1; i <= subbands / 2; i++) {
            p[i][idx] = sat(b[i - 1]);
            p[subbands - i][idx] = sat(b[i - 1]);
        }
        for (int i = 0; i < subbands; i++) {
            long sum = 0;
            for (int j = 0; j < 10; j++) {
                int weight = subbands == 4 ? DEC_WINDOW4[i][state.index + j]
                        : DEC_WINDOW8[i][state.index + j];
                sum += (long) o[i][j] * weight;
            }
            output[i] = sat(rounded(sum, 13));
        }
        state.index = state.index < 9 ? state.index + 1 : 0;
    }

    private static int crc(Frame frame, byte[] data, int offset) {
        int n = frame.channels() * frame.subbands * 4
                + (frame.mode == MODE_JOINT ? frame.subbands : 0);
        int c = CRC8_TABLE[CRC8_TABLE[0x0f ^ (data[offset + 1] & 0xff)]
                ^ (data[offset + 2] & 0xff)];
        for (int i = 4; i < 4 + n / 8; i++) {
            c = CRC8_TABLE[c ^ (data[offset + i] & 0xff)];
        }
        if ((n & 7) != 0) {
            c = ((c << 4) ^ CRC8_TABLE[(c >>> 4)
                    ^ ((data[offset + 4 + n / 8] & 0xff) >>> 4)]) & 0xff;
        }
        return c;
    }

    private static long asr(long value, int bits) {
        return value >> bits;
    }

    private static long rounded(long value, int bits) {
        return asr(value + (1L << (bits - 1)), bits);
    }

    private static int sat(long value) {
        return (int) Math.max(-32768L, Math.min(32767L, value));
    }

    private static int wrap(long value) {
        int unsigned = (int) (value & 0xffffL);
        return unsigned < 32768 ? unsigned : unsigned - 65536;
    }
}
