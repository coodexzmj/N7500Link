package com.bi4byf.n7500link.direct;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Owns local audio rooms, rolling WAV history and bounded live subscriptions.
 * Room IDs are fixed to a safe alphabet and clip lookup never accepts a path.
 */
final class AudioRoomService {
    static final String DEFAULT_ROOM_ID = "default";
    static final String DEFAULT_ROOM_NAME = "N7500 本地台";
    static final int SAMPLE_RATE = RadioAudioStreamParser.OUTPUT_RATE;
    static final int CHANNELS = 1;
    static final int BITS_PER_SAMPLE = 16;
    static final long DEFAULT_CLIP_BYTES = SAMPLE_RATE * 2L * 60L;
    private static final int MAX_ROOM_ID_LENGTH = 32;
    private static final int MAX_ROOM_NAME_LENGTH = 40;

    private static final Pattern ROOM_ID = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern CLIP_ID = Pattern.compile(
            "^clip-[0-9]{10,20}-[0-9]{1,9}$");

    static final class CreateResult {
        final boolean accepted;
        final String code;
        final String id;

        CreateResult(boolean accepted, String code, String id) {
            this.accepted = accepted;
            this.code = code;
            this.id = id;
        }
    }

    static final class ClipHandle {
        final File file;
        final long sizeBytes;
        final String id;
        final String rangeHeader;

        ClipHandle(File file, long sizeBytes, String id, String rangeHeader) {
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.id = id;
            this.rangeHeader = rangeHeader;
        }
    }

    static final class LiveSubscription {
        private final AudioRoomService owner;
        private final Room room;
        private final AudioLiveHub.Client client;
        private boolean released;

        LiveSubscription(AudioRoomService owner, Room room, AudioLiveHub.Client client) {
            this.owner = owner;
            this.room = room;
            this.client = client;
        }

        AudioLiveHub.Client client() { return client; }
        String roomId() { return room.id; }

        void release() {
            owner.releaseLive(this);
        }
    }

    private static final class Room {
        final String id;
        final String name;
        final boolean builtIn;
        final long createdAtMs;
        final File directory;
        final AudioLiveHub hub = new AudioLiveHub();
        WavClipWriter writer;
        int liveClients;

        Room(String id, String name, boolean builtIn, long createdAtMs, File directory) {
            this.id = id;
            this.name = name;
            this.builtIn = builtIn;
            this.createdAtMs = createdAtMs;
            this.directory = directory;
        }
    }

    private final Object lock = new Object();
    private final File rootDirectory;
    private final File roomsFile;
    private final int sampleRate;
    private final long clipBytes;
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private volatile String audioState = "DISCONNECTED";
    private volatile String lastErrorCode;
    private volatile long lastPcmAtMs;
    private volatile boolean stopped;

    AudioRoomService(File rootDirectory) throws IOException {
        this(rootDirectory, SAMPLE_RATE, DEFAULT_CLIP_BYTES);
    }

    AudioRoomService(File rootDirectory, int sampleRate, long clipBytes) throws IOException {
        if (rootDirectory == null) throw new IOException("audio directory unavailable");
        if (sampleRate != SAMPLE_RATE) throw new IOException("unsupported audio rate");
        if (clipBytes < 2048L) throw new IOException("clip size invalid");
        this.rootDirectory = rootDirectory;
        this.roomsFile = new File(rootDirectory, "rooms.tsv");
        this.sampleRate = sampleRate;
        this.clipBytes = clipBytes;
        ensureRoot();
        loadRooms();
        ensureDefaultRoom();
    }

    String audioState() { return audioState; }

    String lastErrorCode() { return lastErrorCode; }

    long lastPcmAtMs() { return lastPcmAtMs; }

    void markLinkState(String state, String errorCode) {
        if (state == null || state.trim().isEmpty()) return;
        audioState = state.trim().toUpperCase(java.util.Locale.US);
        lastErrorCode = errorCode == null || errorCode.trim().isEmpty() ? null : errorCode;
    }

    void appendPcm(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return;
        synchronized (lock) {
            if (stopped) return;
            lastPcmAtMs = System.currentTimeMillis();
            audioState = "RECEIVING";
            // A new valid PCM stream proves that the prior connection attempt
            // recovered. Keep storage errors, but clear stale link errors.
            if (lastErrorCode != null && lastErrorCode.startsWith("AUDIO_")) {
                lastErrorCode = null;
            }
            for (Room room : rooms.values()) {
                if (room.hub.clientCount() > 0) room.hub.broadcast(pcm);
                if (!room.builtIn && room.liveClients <= 0) continue;
                if (room.writer == null) {
                    try {
                        room.writer = createWriter(room);
                        room.writer.open();
                    } catch (IOException e) {
                        lastErrorCode = "CLIP_WRITE_UNAVAILABLE";
                        room.writer = null;
                        continue;
                    }
                }
                try {
                    room.writer.append(pcm, 0, pcm.length);
                } catch (IOException e) {
                    lastErrorCode = "CLIP_WRITE_FAILED";
                    room.writer.close();
                    room.writer = null;
                }
            }
        }
    }

    CreateResult createRoom(String id, String name) {
        String normalizedId = id == null ? "" : id.trim();
        if (!isValidRoomId(normalizedId)) {
            return new CreateResult(false, "INVALID_ROOM_ID", null);
        }
        String normalizedName = safeName(name, normalizedId);
        synchronized (lock) {
            if (stopped) return new CreateResult(false, "AUDIO_STOPPED", null);
            if (rooms.containsKey(normalizedId)) {
                return new CreateResult(false, "ROOM_EXISTS", normalizedId);
            }
            File directory = roomDirectory(normalizedId);
            if (!directory.exists() && !directory.mkdirs()) {
                return new CreateResult(false, "ROOM_STORAGE_FAILED", normalizedId);
            }
            Room room = new Room(normalizedId, normalizedName, false,
                    System.currentTimeMillis(), directory);
            rooms.put(normalizedId, room);
            try {
                saveRooms();
            } catch (IOException e) {
                rooms.remove(normalizedId);
                lastErrorCode = "ROOM_STATE_WRITE_FAILED";
                return new CreateResult(false, "ROOM_STATE_WRITE_FAILED", normalizedId);
            }
            return new CreateResult(true, "ROOM_CREATED", normalizedId);
        }
    }

    boolean hasRoom(String roomId) {
        synchronized (lock) { return rooms.containsKey(roomId); }
    }

    LiveSubscription subscribeLive(String roomId) throws IOException {
        synchronized (lock) {
            if (stopped) throw new IOException("audio stopped");
            Room room = rooms.get(roomId);
            if (room == null) return null;
            AudioLiveHub.Client client = room.hub.subscribe();
            if (client == null) return null;
            if (!room.builtIn && room.liveClients == 0) {
                try {
                    room.writer = createWriter(room);
                    room.writer.open();
                } catch (IOException e) {
                    room.hub.close(client);
                    room.writer = null;
                    lastErrorCode = "CLIP_WRITE_UNAVAILABLE";
                    throw e;
                }
            }
            room.liveClients++;
            return new LiveSubscription(this, room, client);
        }
    }

    private void releaseLive(LiveSubscription subscription) {
        if (subscription == null) return;
        synchronized (lock) {
            if (subscription.released) return;
            subscription.released = true;
            Room room = subscription.room;
            room.hub.close(subscription.client);
            if (room.liveClients > 0) room.liveClients--;
            if (!room.builtIn && room.liveClients == 0 && room.writer != null) {
                room.writer.close();
                room.writer = null;
            }
        }
    }

    String roomsJson() {
        synchronized (lock) {
            StringBuilder json = new StringBuilder(256);
            json.append("{\"ok\":true,\"audioState\":").append(JsonUtil.quote(audioState))
                    .append(",\"lastError\":").append(JsonUtil.nullableString(lastErrorCode))
                    .append(",\"sampleRate\":").append(sampleRate)
                    .append(",\"channels\":").append(CHANNELS)
                    .append(",\"bitsPerSample\":").append(BITS_PER_SAMPLE)
                    .append(",\"lastPcmAt\":")
                    .append(lastPcmAtMs <= 0L ? "null" : JsonUtil.quote(GatewayContract.utc(lastPcmAtMs)))
                    .append(",\"rooms\":[");
            boolean first = true;
            for (Room room : rooms.values()) {
                List<WavClipWriter.Clip> clips = listClips(room);
                if (!first) json.append(',');
                first = false;
                json.append("{\"id\":").append(JsonUtil.quote(room.id))
                        .append(",\"name\":").append(JsonUtil.quote(room.name))
                        .append(",\"default\":").append(room.builtIn)
                        .append(",\"recording\":")
                        .append(room.builtIn || room.liveClients > 0)
                        .append(",\"liveClients\":").append(room.liveClients)
                        .append(",\"clipCount\":").append(clips.size())
                        .append(",\"createdAt\":").append(JsonUtil.quote(GatewayContract.utc(room.createdAtMs)))
                        .append('}');
            }
            return json.append("]}").toString();
        }
    }

    String clipsJson(String roomId) {
        synchronized (lock) {
            Room room = rooms.get(roomId);
            if (room == null) return null;
            List<WavClipWriter.Clip> clips = listClips(room);
            StringBuilder json = new StringBuilder(256);
            json.append("{\"ok\":true,\"roomId\":").append(JsonUtil.quote(room.id))
                    .append(",\"roomName\":").append(JsonUtil.quote(room.name))
                    .append(",\"clips\":[");
            for (int i = 0; i < clips.size(); i++) {
                WavClipWriter.Clip clip = clips.get(i);
                if (i > 0) json.append(',');
                json.append("{\"id\":").append(JsonUtil.quote(clip.id))
                        .append(",\"createdAt\":")
                        .append(JsonUtil.quote(GatewayContract.utc(clip.modifiedAtMs)))
                        .append(",\"durationMs\":").append(clip.durationMs)
                        .append(",\"sizeBytes\":").append(clip.sizeBytes)
                        .append('}');
            }
            return json.append("]}").toString();
        }
    }

    ClipHandle findClip(String roomId, String clipId) {
        return findClip(roomId, clipId, null);
    }

    ClipHandle findClip(String roomId, String clipId, String rangeHeader) {
        if (!isValidRoomId(roomId) || clipId == null || !CLIP_ID.matcher(clipId).matches()) {
            return null;
        }
        synchronized (lock) {
            Room room = rooms.get(roomId);
            if (room == null) return null;
            File file = new File(room.directory, clipId + ".wav");
            if (!file.isFile()) return null;
            try {
                String canonicalRoom = room.directory.getCanonicalPath();
                String canonicalFile = file.getCanonicalPath();
                if (!canonicalFile.equals(canonicalRoom + File.separator + clipId + ".wav")) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
            long size = file.length();
            if (size <= WavClipWriter.HEADER_BYTES) return null;
            return new ClipHandle(file, size, clipId, rangeHeader);
        }
    }

    void stop() {
        synchronized (lock) {
            if (stopped) return;
            stopped = true;
            audioState = "STOPPED";
            for (Room room : rooms.values()) {
                room.hub.closeAll();
                room.liveClients = 0;
                if (room.writer != null) {
                    room.writer.close();
                    room.writer = null;
                }
            }
        }
    }

    static boolean isValidRoomId(String id) {
        return id != null && id.length() <= MAX_ROOM_ID_LENGTH
                && ROOM_ID.matcher(id).matches()
                && !id.contains("..");
    }

    static boolean isValidClipId(String id) {
        return id != null && CLIP_ID.matcher(id).matches();
    }

    private List<WavClipWriter.Clip> listClips(Room room) {
        if (room.writer != null) return room.writer.clips();
        return WavClipWriter.scanClips(room.directory, sampleRate, CHANNELS, false);
    }

    private WavClipWriter createWriter(Room room) {
        return new WavClipWriter(room.directory, sampleRate, CHANNELS, clipBytes);
    }

    private File roomDirectory(String id) {
        return new File(new File(rootDirectory, "rooms"), id);
    }

    private void ensureRoot() throws IOException {
        if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
            throw new IOException("audio root cannot be created");
        }
        if (!rootDirectory.isDirectory()) throw new IOException("audio root invalid");
        File roomsDirectory = new File(rootDirectory, "rooms");
        if (!roomsDirectory.exists() && !roomsDirectory.mkdirs()) {
            throw new IOException("audio room directory cannot be created");
        }
    }

    private void ensureDefaultRoom() throws IOException {
        Room existing = rooms.get(DEFAULT_ROOM_ID);
        File directory = roomDirectory(DEFAULT_ROOM_ID);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("default audio room cannot be created");
        }
        Room defaultRoom = existing == null
                ? new Room(DEFAULT_ROOM_ID, DEFAULT_ROOM_NAME, true, 0L, directory)
                : new Room(DEFAULT_ROOM_ID, DEFAULT_ROOM_NAME, true, existing.createdAtMs, directory);
        rooms.remove(DEFAULT_ROOM_ID);
        rooms.put(DEFAULT_ROOM_ID, defaultRoom);
        defaultRoom.writer = createWriter(defaultRoom);
        defaultRoom.writer.open();
        if (existing == null) saveRooms();
    }

    private void loadRooms() throws IOException {
        if (!roomsFile.isFile()) return;
        List<String> lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(roomsFile), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] fields = line.split("\\t", 3);
                if (fields.length != 3) continue;
                String id = fields[0];
                if (!isValidRoomId(id) || DEFAULT_ROOM_ID.equals(id)) continue;
                long created;
                try { created = Long.parseLong(fields[1]); }
                catch (NumberFormatException e) { continue; }
                String name = safeName(fields[2], id);
                File directory = roomDirectory(id);
                if (!directory.exists() && !directory.mkdirs()) continue;
                WavClipWriter.repairDirectory(directory, sampleRate, CHANNELS);
                rooms.put(id, new Room(id, name, false, created, directory));
            }
        } finally {
            reader.close();
        }
    }

    private void saveRooms() throws IOException {
        File temporary = new File(rootDirectory, "rooms.tsv.tmp");
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8));
        try {
            writer.write("#N1Direct audio rooms v1\n");
            for (Room room : rooms.values()) {
                if (room.builtIn) continue;
                writer.write(room.id);
                writer.write('\t');
                writer.write(Long.toString(room.createdAtMs));
                writer.write('\t');
                writer.write(room.name);
                writer.write('\n');
            }
        } finally {
            writer.close();
        }
        if (roomsFile.exists() && !roomsFile.delete()) {
            temporary.delete();
            throw new IOException("room state cannot be replaced");
        }
        if (!temporary.renameTo(roomsFile)) {
            temporary.delete();
            throw new IOException("room state cannot be committed");
        }
    }

    private static String safeName(String value, String fallback) {
        String source = value == null ? "" : value.trim();
        StringBuilder clean = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '\t' && c != '\r' && c != '\n') {
                clean.append(c);
            }
        }
        String result = clean.toString().trim();
        if (result.isEmpty()) result = fallback == null ? "本地音频房" : fallback;
        if (result.length() > MAX_ROOM_NAME_LENGTH) {
            result = result.substring(0, MAX_ROOM_NAME_LENGTH);
        }
        return result;
    }
}