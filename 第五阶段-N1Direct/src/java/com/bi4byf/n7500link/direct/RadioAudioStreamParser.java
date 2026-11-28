package com.bi4byf.n7500link.direct;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Parses the N7500 audio RFCOMM stream, decodes SBC/mSBC frames and normalizes
 * PCM to 32 kHz, 16-bit, mono, little-endian. A malformed frame is discarded
 * together with the remainder of its packet; PCM decoded before the bad frame
 * is retained.
 */
final class RadioAudioStreamParser {
    static final int OUTPUT_RATE = 32000;
    private static final int MAX_PACKET_BYTES = 65536;

    private final SbcDecoder decoder = new SbcDecoder();
    private final LinearResampler resampler = new LinearResampler(OUTPUT_RATE);
    private final byte[] packet = new byte[MAX_PACKET_BYTES];
    private int packetSize;
    private boolean started;
    private boolean escaped;
    private boolean discarding;
    private boolean packetEscapeError;

    private long inputBytes;
    private long packets;
    private long audioPackets;
    private long ignoredPackets;
    private long decodedFrames;
    private long rejectedFrames;
    private long escapeErrors;
    private long oversizedPackets;
    private long outputSamples;

    byte[] feed(byte[] data, int length) {
        if (data == null || length <= 0) return new byte[0];
        int count = Math.min(length, data.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        inputBytes += count;
        for (int i = 0; i < count; i++) {
            byte value = data[i];
            if (discarding) {
                if (value == 0x7e) {
                    discarding = false;
                    beginPacket();
                }
                continue;
            }
            if (escaped) {
                escaped = false;
                if (value == 0x7e) {
                    escapeErrors++;
                    packetEscapeError = true;
                    finishPacket(output);
                    beginPacket();
                } else {
                    append((byte) (value ^ 0x20), output);
                }
                continue;
            }
            if (value == 0x7e) {
                finishPacket(output);
                beginPacket();
            } else if (started) {
                if (value == 0x7d) {
                    escaped = true;
                } else {
                    append(value, output);
                }
            }
        }
        byte[] pcm = output.toByteArray();
        outputSamples += pcm.length / 2;
        return pcm;
    }

    void reset() {
        packetSize = 0;
        started = false;
        escaped = false;
        discarding = false;
        packetEscapeError = false;
        decoder.reset();
        resampler.reset();
        inputBytes = 0;
        packets = 0;
        audioPackets = 0;
        ignoredPackets = 0;
        decodedFrames = 0;
        rejectedFrames = 0;
        escapeErrors = 0;
        oversizedPackets = 0;
        outputSamples = 0;
    }

    long inputBytes() { return inputBytes; }
    long packets() { return packets; }
    long audioPackets() { return audioPackets; }
    long ignoredPackets() { return ignoredPackets; }
    long decodedFrames() { return decodedFrames; }
    long rejectedFrames() { return rejectedFrames; }
    long escapeErrors() { return escapeErrors; }
    long oversizedPackets() { return oversizedPackets; }
    long outputSamples() { return outputSamples; }
    boolean hasDanglingEscape() { return escaped; }

    private void beginPacket() {
        packetSize = 0;
        started = true;
        escaped = false;
        packetEscapeError = false;
    }

    private void append(byte value, ByteArrayOutputStream output) {
        if (!started) return;
        if (packetSize >= MAX_PACKET_BYTES) {
            oversizedPackets++;
            discarding = true;
            packetSize = 0;
            started = false;
            escaped = false;
            packetEscapeError = false;
            return;
        }
        packet[packetSize++] = value;
    }

    private void finishPacket(ByteArrayOutputStream output) {
        if (!started || packetSize == 0) {
            clearPacket();
            return;
        }
        packets++;
        if (packetEscapeError) {
            // SLIP decoding never forwards a packet that contained an invalid
            // escape sequence. Keeping the partially decoded PCM would hide a
            // corrupted delimiter and let a malformed frame reach history.
            ignoredPackets++;
            clearPacket();
            return;
        }
        int command;
        int payloadOffset;
        int payloadLength;
        int first = packet[0] & 0xff;
        if (first == 0xff && packetSize >= 2 && packet[1] == 0x03) {
            // FF 03 carries a 16-bit big-endian command. The final two bytes
            // are a checksum; the non-strict audio parser intentionally accepts
            // command 9 without enforcing it, matching the N7500 audio stream.
            if (packetSize < 6) {
                ignoredPackets++;
                clearPacket();
                return;
            }
            command = ((packet[2] & 0xff) << 8) | (packet[3] & 0xff);
            payloadOffset = 4;
            payloadLength = packetSize - 6;
        } else if ((first & 0x80) != 0) {
            // Extended packet: high bits are part of the command. A 0xC0 header
            // additionally reserves two trailing checksum bytes.
            if (packetSize < 2) {
                ignoredPackets++;
                clearPacket();
                return;
            }
            command = ((first & 0x3f) << 8) | (packet[1] & 0xff);
            payloadOffset = 2;
            payloadLength = (first & 0xc0) == 0xc0
                    ? packetSize - 4 : packetSize - 2;
        } else {
            command = first & 0x7f;
            payloadOffset = 1;
            payloadLength = packetSize - 1;
        }
        if (command != 9 || payloadLength <= 0) {
            ignoredPackets++;
            clearPacket();
            return;
        }
        audioPackets++;
        decodePacket(output, payloadOffset, payloadLength);
        clearPacket();
    }

    private void clearPacket() {
        packetSize = 0;
        started = false;
        escaped = false;
        packetEscapeError = false;
    }

    private void decodePacket(ByteArrayOutputStream output, int payloadOffset,
                              int payloadLength) {
        int position = payloadOffset;
        int end = payloadOffset + payloadLength;
        while (position < end) {
            int remaining = end - position;
            try {
                SbcDecoder.Frame frame = decoder.parseHeader(packet, position, remaining);
                int channels = frame.mode == SbcDecoder.MODE_MONO ? 1 : 2;
                int expectedSamples = frame.sampleCount() * channels;
                int[] decoded = new int[expectedSamples];
                int actualSamples = decoder.decodeFrame(packet, position, remaining, decoded);
                if (actualSamples != expectedSamples) {
                    rejectedFrames++;
                    return;
                }
                decodedFrames++;
                int[] mono = downmix(decoded, channels, frame.sampleCount());
                writeResampled(output, mono, frame.sampleRate());
                position += frame.size();
            } catch (SbcDecoder.DecodeException error) {
                rejectedFrames++;
                return;
            }
        }
    }

    private void writeResampled(ByteArrayOutputStream output, int[] mono, int inputRate) {
        int[] normalized = resampler.process(mono, inputRate);
        for (int i = 0; i < normalized.length; i++) {
            int value = normalized[i];
            output.write(value & 0xff);
            output.write((value >>> 8) & 0xff);
        }
    }

    private static int[] downmix(int[] decoded, int channels, int sampleCount) {
        if (channels == 1) return decoded;
        int[] mono = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int left = decoded[i * 2];
            int right = decoded[i * 2 + 1];
            mono[i] = (left + right) / 2;
        }
        return mono;
    }

    private static final class LinearResampler {
        private final int outputRate;

        LinearResampler(int outputRate) { this.outputRate = outputRate; }

        void reset() { }

        int[] process(int[] input, int inputRate) {
            if (input.length == 0 || inputRate <= 0) return new int[0];
            if (inputRate == outputRate) return Arrays.copyOf(input, input.length);
            double step = (double) inputRate / outputRate;
            int count = Math.max(1, (int) Math.ceil(input.length / step));
            int[] output = new int[count];
            for (int i = 0; i < count; i++) {
                double position = i * step;
                if (position >= input.length - 1) {
                    output[i] = input[input.length - 1];
                    continue;
                }
                int left = (int) Math.floor(position);
                int right = Math.min(left + 1, input.length - 1);
                double fraction = position - left;
                int value = (int) Math.round(input[left]
                        + (input[right] - input[left]) * fraction);
                output[i] = Math.max(-32768, Math.min(32767, value));
            }
            return output;
        }
    }
}
