package com.bi4byf.n7500link.direct;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Appends 32 kHz / 16-bit / mono PCM to standard RIFF/WAVE clips and rolls to a
 * new clip at a fixed data-byte boundary. Every file name is generated locally;
 * callers never supply a path.
 */
final class WavClipWriter {
    static final int HEADER_BYTES = 44;
    static final int BITS_PER_SAMPLE = 16;
    private static final long HEADER_REFRESH_MS = 1000L;
    private static final Pattern CLIP_NAME = Pattern.compile(
            "^clip-[0-9]{10,20}-[0-9]{1,9}\\.wav$");

    static final class Clip {
        final String id;
        final File file;
        final long sizeBytes;
        final long modifiedAtMs;
        final long durationMs;

        Clip(String id, File file, long sizeBytes, long modifiedAtMs, long durationMs) {
            this.id = id;
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.modifiedAtMs = modifiedAtMs;
            this.durationMs = durationMs;
        }
    }

    private final File directory;
    private final int sampleRate;
    private final int channels;
    private final long maxDataBytes;
    private final int blockAlign;
    private final int byteRate;

    private RandomAccessFile current;
    private File currentFile;
    private long currentDataBytes;
    private long lastHeaderAt;
    private boolean closed;
    private int sequence;

    WavClipWriter(File directory, int sampleRate, int channels, long maxDataBytes) {
        if (directory == null) throw new IllegalArgumentException("directory");
        if (sampleRate < 8000 || sampleRate > 192000) {
            throw new IllegalArgumentException("sampleRate");
        }
        if (channels != 1) throw new IllegalArgumentException("mono only");
        if (maxDataBytes < 2048L) throw new IllegalArgumentException("maxDataBytes");
        this.directory = directory;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.maxDataBytes = maxDataBytes;
        this.blockAlign = channels * (BITS_PER_SAMPLE / 8);
        this.byteRate = sampleRate * blockAlign;
    }

    static WavClipWriter forOneMinute(File directory, int sampleRate, int channels) {
        return new WavClipWriter(directory, sampleRate, channels, sampleRate * 2L * 60L);
    }

    synchronized void open() throws IOException {
        if (!closed && current != null) return;
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("clip directory cannot be created");
        }
        if (!directory.isDirectory()) throw new IOException("clip path is not a directory");
        repairDirectory(directory, sampleRate, channels);
        closed = false;
        openNewClip();
    }

    synchronized void append(byte[] pcm, int offset, int length) throws IOException {
        if (closed) throw new IOException("writer closed");
        if (pcm == null || length <= 0) return;
        if (offset < 0 || length < 0 || offset > pcm.length - length) {
            throw new IndexOutOfBoundsException("pcm range");
        }
        int remaining = length - (length % blockAlign);
        int position = offset;
        while (remaining > 0) {
            if (current == null || currentDataBytes >= maxDataBytes) {
                rollClip();
            }
            long available = maxDataBytes - currentDataBytes;
            int count = (int) Math.min((long) remaining, available);
            count -= count % blockAlign;
            if (count <= 0) {
                rollClip();
                continue;
            }
            current.write(pcm, position, count);
            currentDataBytes += count;
            position += count;
            remaining -= count;
            long now = System.currentTimeMillis();
            if (now - lastHeaderAt >= HEADER_REFRESH_MS
                    || currentDataBytes >= maxDataBytes) {
                writeHeader(current, currentDataBytes);
                lastHeaderAt = now;
            }
        }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        finishCurrent();
    }

    synchronized List<Clip> clips() {
        if (currentFile != null && current != null) {
            try {
                writeHeader(current, currentDataBytes);
            } catch (IOException ignored) {
            }
        }
        List<Clip> clips = scanClips(directory, sampleRate, channels, false);
        return clips;
    }

    synchronized long currentDataBytes() {
        return currentDataBytes;
    }

    synchronized boolean isOpen() {
        return !closed && current != null;
    }

    private void rollClip() throws IOException {
        finishCurrent();
        if (closed) return;
        openNewClip();
    }

    private void finishCurrent() {
        if (current == null) return;
        RandomAccessFile target = current;
        File file = currentFile;
        long bytes = currentDataBytes;
        current = null;
        currentFile = null;
        currentDataBytes = 0L;
        try {
            if (bytes > 0L) writeHeader(target, bytes);
        } catch (IOException ignored) {
        } finally {
            try { target.close(); } catch (IOException ignored) {}
        }
        if (file != null && bytes <= 0L) {
            // Header-only clips are not history and should not consume space.
            try { file.delete(); } catch (SecurityException ignored) {}
        }
    }

    private void openNewClip() throws IOException {
        long stamp;
        File candidate;
        do {
            stamp = System.currentTimeMillis();
            sequence++;
            candidate = new File(directory, "clip-" + stamp + "-" + sequence + ".wav");
        } while (candidate.exists() && sequence < 1000000);
        RandomAccessFile opened = null;
        try {
            opened = new RandomAccessFile(candidate, "rw");
            opened.setLength(0L);
            writeHeader(opened, 0L);
            currentFile = candidate;
            current = opened;
            currentDataBytes = 0L;
            lastHeaderAt = System.currentTimeMillis();
        } catch (IOException e) {
            if (opened != null) {
                try { opened.close(); } catch (IOException ignored) {}
            }
            try { candidate.delete(); } catch (SecurityException ignored) {}
            throw e;
        }
    }

    private void writeHeader(RandomAccessFile file, long dataBytes) throws IOException {
        long safeData = Math.max(0L, dataBytes);
        long total = HEADER_BYTES + safeData;
        file.seek(0L);
        file.writeBytes("RIFF");
        writeLeInt(file, checkedLength(total - 8L));
        file.writeBytes("WAVE");
        file.writeBytes("fmt ");
        writeLeInt(file, 16);
        writeLeShort(file, 1);
        writeLeShort(file, channels);
        writeLeInt(file, sampleRate);
        writeLeInt(file, byteRate);
        writeLeShort(file, blockAlign);
        writeLeShort(file, BITS_PER_SAMPLE);
        file.writeBytes("data");
        writeLeInt(file, checkedLength(safeData));
    }

    private static int checkedLength(long value) throws IOException {
        if (value < 0L || value > 0x7ffffff0L) {
            throw new IOException("WAV clip exceeds RIFF limit");
        }
        return (int) value;
    }

    private static void writeLeShort(RandomAccessFile file, int value) throws IOException {
        file.write(value & 0xff);
        file.write((value >>> 8) & 0xff);
    }

    private static void writeLeInt(RandomAccessFile file, int value) throws IOException {
        file.write(value & 0xff);
        file.write((value >>> 8) & 0xff);
        file.write((value >>> 16) & 0xff);
        file.write((value >>> 24) & 0xff);
    }

    static void repairDirectory(File directory, int sampleRate, int channels) {
        if (directory == null || !directory.isDirectory()) return;
        int blockAlign = channels * 2;
        int byteRate = sampleRate * blockAlign;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile() || !CLIP_NAME.matcher(file.getName()).matches()) {
                continue;
            }
            long length = file.length();
            if (length <= HEADER_BYTES) {
                try { file.delete(); } catch (SecurityException ignored) {}
                continue;
            }
            RandomAccessFile target = null;
            try {
                target = new RandomAccessFile(file, "rw");
                rebuildHeader(target, length, sampleRate, channels, byteRate, blockAlign);
            } catch (IOException ignored) {
            } finally {
                if (target != null) {
                    try { target.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    static List<Clip> scanClips(File directory, final int sampleRate, int channels,
                                boolean repair) {
        if (repair) repairDirectory(directory, sampleRate, channels);
        List<Clip> result = new ArrayList<>();
        if (directory == null || !directory.isDirectory()) return result;
        File[] files = directory.listFiles();
        if (files == null) return result;
        long bytesPerSecond = sampleRate * 2L;
        for (File file : files) {
            if (file == null || !file.isFile() || !CLIP_NAME.matcher(file.getName()).matches()) {
                continue;
            }
            long size = file.length();
            if (size <= HEADER_BYTES) continue;
            String name = file.getName();
            String id = name.substring(0, name.length() - 4);
            long dataBytes = size - HEADER_BYTES;
            long durationMs = (dataBytes * 1000L) / bytesPerSecond;
            result.add(new Clip(id, file, size, file.lastModified(), durationMs));
        }
        Collections.sort(result, new Comparator<Clip>() {
            @Override public int compare(Clip left, Clip right) {
                int byTime = Long.compare(right.modifiedAtMs, left.modifiedAtMs);
                return byTime != 0 ? byTime : right.id.compareTo(left.id);
            }
        });
        return result;
    }

    private static void rebuildHeader(RandomAccessFile file, long length, int sampleRate,
                                      int channels, int byteRate, int blockAlign)
            throws IOException {
        long dataBytes = length - HEADER_BYTES;
        dataBytes -= dataBytes % blockAlign;
        // Trim a partial final sample if an interrupted write left one behind.
        if (HEADER_BYTES + dataBytes != length) file.setLength(HEADER_BYTES + dataBytes);
        file.seek(0L);
        file.writeBytes("RIFF");
        writeLeInt(file, checkedLength(HEADER_BYTES + dataBytes - 8L));
        file.writeBytes("WAVE");
        file.writeBytes("fmt ");
        writeLeInt(file, 16);
        writeLeShort(file, 1);
        writeLeShort(file, channels);
        writeLeInt(file, sampleRate);
        writeLeInt(file, byteRate);
        writeLeShort(file, blockAlign);
        writeLeShort(file, BITS_PER_SAMPLE);
        file.writeBytes("data");
        writeLeInt(file, checkedLength(dataBytes));
    }
}